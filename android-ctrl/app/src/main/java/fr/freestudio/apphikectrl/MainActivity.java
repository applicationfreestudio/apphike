package fr.freestudio.apphikectrl;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Treadmill control test (FTMS), before the final native App Hike.
 * Connects to the treadmill and reconnects by itself, shows the measured speed and incline, sends speed and incline
 * targets (+/- buttons), and drives the belt with Avance / Pause / Stop.
 * If the belt stops while the app was asking it to move (button on the treadmill, safety key, speed at 0 for 2 s),
 * the app switches to Pause and keeps as targets the last stable speed and incline, sent again on Avance.
 * A speed or incline changed on the treadmill while it runs becomes the new target.
 * Option "clone": a Bluetooth server re-sends the treadmill as an FTMS treadmill for a watch or another app (FtmsServer).
 */
public class MainActivity extends Activity {
    static final UUID FTMS = uuid16(0x1826), CP = uuid16(0x2AD9), STATUS = uuid16(0x2ADA), DATA = uuid16(0x2ACD), CCCD = uuid16(0x2902),
            FEATURE = uuid16(0x2ACC), SPEED_RANGE = uuid16(0x2AD4), INCL_RANGE = uuid16(0x2AD5);
    static final int STEP_TIMEOUT_MS = 8000, RETRY_MS = 1500, ANSWER_MS = 2500;
    static final int OFF = 0, SEARCH = 1, CONNECTING = 2, READY = 3;
    static final int STOP = 0, RUN = 1, PAUSE = 2;

    static UUID uuid16(int v) { return UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v)); }

    final Handler h = new Handler(Looper.getMainLooper());
    final StringBuilder journal = new StringBuilder();
    BluetoothAdapter adapter;
    BluetoothDevice device;
    BluetoothGatt gatt;
    BluetoothGattCharacteristic cpChar;
    ScanCallback scanCb;
    boolean want, setupDone, skylink;
    int conn = OFF, mode = STOP, tries;
    String step = "", tmMsg = "—", controlMsg = "";
    final List<BluetoothGattCharacteristic> subs = new ArrayList<>(), reads = new ArrayList<>();
    final Runnable readTimeout = () -> { if (!reads.isEmpty()) { log("lecture " + rname(reads.get(0).getUuid()) + " : pas de réponse, ignorée"); reads.remove(0); } readNext(); };
    FtmsServer clone;
    SharedPreferences prefs;
    TextView tvClone;
    Button bClone;

    // ranges and targets
    double sMin = 1, sMax = 16, iMin = 0, iMax = 24;
    double tSp = 3.0, tInc = 0;
    // measures
    Double mSp, mInc;
    long nData, lastDataAt;
    // detection
    long lastOwn, lastCmd, settleAt, z0;
    boolean pausedByTm;
    double spRef = -1, incRef = -99; long spSince, incSince;   // stability of the measures
    double stableSp = -1, stableInc = 0;                         // last stable values while the belt was turning

    // control point queue: one command at a time, each one waits for the treadmill's answer
    static class Cmd { String label; byte[] v; Cmd(String l, byte[] v) { label = l; this.v = v; } }
    final ArrayDeque<Cmd> queue = new ArrayDeque<>();
    Cmd pend; long pendAt;
    final Runnable pendTimeout = () -> { if (pend != null) { log("← " + pend.label + " : PAS DE RÉPONSE"); pend = null; next(); } };
    final Runnable watchdog = () -> fail("pas de réponse à l'étape « " + step + " »");
    final Runnable sendDebounced = this::sendTargets;

    TextView tvConn, tvBelt, tvMode, tvMsg, tvSp, tvInc, tvTSp, tvTInc, tvLog;
    Button bConn;
    ScrollView logScroll;

    // ---------------- UI ----------------
    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    TextView tv(LinearLayout parent, float size, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        parent.addView(t);
        return t;
    }

    Button btn(LinearLayout parent, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        parent.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return b;
    }

    LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(r);
        return r;
    }

    TextView label(LinearLayout parent, String s) {
        TextView t = tv(parent, 13, false);
        t.setText(s);
        t.setPadding(0, dp(10), 0, 0);
        t.setAlpha(.7f);
        return t;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(28), dp(14), dp(10));

        TextView title = tv(root, 20, true);
        title.setText("Pilotage du tapis · test");
        tvConn = tv(root, 14, true);
        tvConn.setPadding(0, dp(4), 0, 0);

        label(root, "État du tapis");
        tvBelt = tv(root, 26, true);
        tvMode = tv(root, 15, false);
        tvMsg = tv(root, 13, false);
        tvMsg.setAlpha(.8f);

        label(root, "Mesures du tapis");
        LinearLayout mr = row(root);
        tvSp = tv(mr, 24, true);
        tvSp.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        tvInc = tv(mr, 24, true);
        tvInc.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        label(root, "Consigne de vitesse");
        LinearLayout sr = row(root);
        btn(sr, "−0,5", v -> setSp(tSp - 0.5));
        btn(sr, "−0,1", v -> setSp(tSp - 0.1));
        tvTSp = tv(sr, 20, true);
        tvTSp.setGravity(Gravity.CENTER);
        tvTSp.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f));
        btn(sr, "+0,1", v -> setSp(tSp + 0.1));
        btn(sr, "+0,5", v -> setSp(tSp + 0.5));

        label(root, "Consigne d'inclinaison");
        LinearLayout ir = row(root);
        btn(ir, "−5", v -> setInc(tInc - 5));
        btn(ir, "−1", v -> setInc(tInc - 1));
        tvTInc = tv(ir, 20, true);
        tvTInc.setGravity(Gravity.CENTER);
        tvTInc.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f));
        btn(ir, "+1", v -> setInc(tInc + 1));
        btn(ir, "+5", v -> setInc(tInc + 5));

        LinearLayout cr = row(root);
        cr.setPadding(0, dp(10), 0, 0);
        btn(cr, "▶ Avance", v -> doRun());
        btn(cr, "⏸ Pause", v -> doPause());
        btn(cr, "■ Stop", v -> doStop());

        LinearLayout or = row(root);
        bConn = btn(or, "Déconnecter", v -> { if (want) disconnect(); else connect(); });
        btn(or, "Copier le journal", v -> {
            ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("journal", journal.toString()));
            log("journal copié");
        });
        btn(or, "Partager", v -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, journal.toString()), "Partager le journal")));

        label(root, "Émettre comme un tapis (clone pour une montre ou une autre app)");
        LinearLayout kr = row(root);
        bClone = btn(kr, "Activer le clone", v -> setClone(!clone.on));
        tvClone = tv(root, 13, false);

        label(root, "Journal");
        logScroll = new ScrollView(this);
        tvLog = new TextView(this);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextSize(10.5f);
        tvLog.setTextIsSelectable(true);
        logScroll.addView(tvLog);
        root.addView(logScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        adapter = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        log("Pilotage du tapis · " + new SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(new Date()) + " · Android " + Build.VERSION.RELEASE + " · " + Build.MODEL);
        prefs = getSharedPreferences("pilotage", MODE_PRIVATE);
        clone = new FtmsServer(this, new FtmsServer.Listener() {
            @Override public void log(String s) { MainActivity.this.log(s); }
            @Override public void changed() { ui(); }
        });
        ui();
        if (hasPermissions()) { connect(); if (prefs.getBoolean("clone", false)) clone.start(); } else askPermissions();
    }

    boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= 31)
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    void askPermissions() {
        if (Build.VERSION.SDK_INT >= 31) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE}, 1);
        else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (hasPermissions()) { connect(); if (prefs.getBoolean("clone", false)) clone.start(); } else { tmMsg = "Autorisation Bluetooth refusée : accorde « Appareils à proximité » dans les réglages de l'app."; ui(); }
    }

    static String f1(double v) { return String.format(Locale.FRANCE, "%.1f", v); }
    static String now() { return new SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(new Date()); }

    void log(String s) {
        journal.append(now()).append("  ").append(s).append('\n');
        if (journal.length() > 60000) journal.delete(0, journal.indexOf("\n", 20000) + 1);
        tvLog.setText(journal);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    boolean turning() { return mSp != null && mSp >= 0.05; }

    void ui() {
        String name = device != null && device.getName() != null ? device.getName() : "tapis";
        switch (conn) {
            case READY: tvConn.setText("● Connecté à " + name + controlMsg); tvConn.setTextColor(0xFF43A047); break;
            case CONNECTING: tvConn.setText("◌ Connexion à " + name + "… (" + step + ", essai " + tries + ")"); tvConn.setTextColor(0xFFFB8C00); break;
            case SEARCH: tvConn.setText("◌ Recherche du tapis…" + (tries > 0 ? " (essai " + tries + ")" : "")); tvConn.setTextColor(0xFFFB8C00); break;
            default: tvConn.setText("○ Déconnecté"); tvConn.setTextColor(0xFF9E9E9E);
        }
        boolean live = conn == READY && SystemClock.elapsedRealtime() - lastDataAt < 3000;
        if (!live) { tvBelt.setText("—"); tvBelt.setTextColor(0xFF9E9E9E); }
        else if (turning()) { tvBelt.setText("EN MARCHE"); tvBelt.setTextColor(0xFF43A047); }
        else { tvBelt.setText("À L'ARRÊT"); tvBelt.setTextColor(0xFFE53935); }
        tvMode.setText("Commande de l'app : " + (mode == RUN ? "Avance" : mode == PAUSE ? (pausedByTm ? "Pause (détectée sur le tapis)" : "Pause") : "Stop"));
        tvMsg.setText("Dernier message du tapis : " + tmMsg);
        tvSp.setText(live && mSp != null ? f1(mSp) + " km/h" : "— km/h");
        tvInc.setText(live && mInc != null ? f1(mInc) + " %" : "— %");
        tvTSp.setText(f1(tSp) + " km/h");
        tvTInc.setText(f1(tInc) + " %");
        bConn.setText(want ? "Déconnecter" : "Connecter");
        if (clone != null) {
            bClone.setText(clone.on ? "Désactiver le clone" : "Activer le clone");
            String cs;
            if (!clone.on) cs = clone.error.isEmpty() ? "Clone désactivé." : "Clone arrêté : " + clone.error;
            else if (!clone.advertising) cs = clone.error.isEmpty() ? "Démarrage de l'émission…" : "Problème : " + clone.error;
            else cs = "● Émet comme un tapis sous le nom « " + clone.name() + " »" + (clone.clientCount() > 0 ? " · connecté : " + clone.clientNames() : " · aucun appareil connecté") + (conn == READY ? "" : " · en attente du vrai tapis");
            tvClone.setText(cs);
            tvClone.setTextColor(clone.on && clone.advertising ? (clone.clientCount() > 0 ? 0xFF43A047 : 0xFFFB8C00) : 0xFF9E9E9E);
        }
    }

    final Runnable tick = new Runnable() { @Override public void run() { ui(); h.postDelayed(this, 1000); } };
    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() { h.removeCallbacks(tick); super.onPause(); }

    void setClone(boolean on) {
        if (on && !hasPermissions()) { askPermissions(); return; }
        prefs.edit().putBoolean("clone", on).apply();
        if (on) clone.start(); else clone.stop();
        ui();
    }

    // ---------------- targets ----------------
    double cap(double inc) { return skylink ? (inc < 0 ? 6 : inc <= 15 ? 13 : inc <= 26 ? 8 : 6) : sMax; }
    static double r1(double v) { return Math.round(v * 10) / 10.0; }

    void setSp(double v) {
        tSp = r1(Math.max(sMin, Math.min(Math.min(sMax, cap(tInc)), v)));
        ui();
        if (mode == RUN) { h.removeCallbacks(sendDebounced); h.postDelayed(sendDebounced, 400); }
    }

    void setInc(double v) {
        tInc = r1(Math.max(iMin, Math.min(iMax, v)));
        if (tSp > cap(tInc)) { tSp = cap(tInc); log("vitesse ramenée à " + f1(tSp) + " km/h (maximum à " + f1(tInc) + " %)"); }
        ui();
        if (mode == RUN) { h.removeCallbacks(sendDebounced); h.postDelayed(sendDebounced, 400); }
    }

    void sendTargets() {
        if (conn != READY || mode != RUN) return;
        int s = (int) Math.round(tSp * 100), i = (int) Math.round(tInc * 10);
        send("Vitesse " + f1(tSp) + " km/h", new byte[]{0x02, (byte) s, (byte) (s >> 8)});
        send("Inclinaison " + f1(tInc) + " %", new byte[]{0x03, (byte) i, (byte) (i >> 8)});
    }

    // ---------------- buttons ----------------
    void doRun() {
        if (conn != READY) { log("Avance ignorée : tapis non connecté"); return; }
        boolean was = turning();
        mode = RUN; pausedByTm = false; z0 = 0;
        long t = SystemClock.elapsedRealtime();
        lastOwn = t; settleAt = t + 12000;
        if (!was) {
            send("Démarrage", new byte[]{0x07});
            h.postDelayed(() -> { if (mode == RUN) sendTargets(); }, 4000);
        } else sendTargets();
        ui();
    }

    void doPause() {
        if (conn != READY) return;
        mode = PAUSE; pausedByTm = false; lastOwn = SystemClock.elapsedRealtime();
        h.removeCallbacks(sendDebounced);
        send("Pause", new byte[]{0x08, 0x02});
        ui();
    }

    void doStop() {
        if (conn != READY) return;
        mode = STOP; pausedByTm = false; lastOwn = SystemClock.elapsedRealtime();
        h.removeCallbacks(sendDebounced);
        send("Stop", new byte[]{0x08, 0x01});
        ui();
    }

    void pauseDetected(String why) {
        if (mode != RUN) return;
        mode = PAUSE; pausedByTm = true; z0 = 0;
        h.removeCallbacks(sendDebounced);
        if (stableSp >= sMin) tSp = r1(Math.min(stableSp, cap(stableInc)));
        tInc = r1(Math.max(iMin, Math.min(iMax, stableInc)));
        log("⏸ PAUSE détectée sur le tapis (" + why + ") · consignes : " + f1(tSp) + " km/h, " + f1(tInc) + " % (renvoyées à l'avance)");
        ui();
    }

    void resumeDetected(String why) {
        if (mode != PAUSE || !pausedByTm) return;
        mode = RUN; pausedByTm = false;
        long t = SystemClock.elapsedRealtime();
        settleAt = t + 4000; lastOwn = t;
        log("▶ REPRISE détectée sur le tapis (" + why + ") · envoi des consignes");
        sendTargets();
        ui();
    }

    // ---------------- control point queue ----------------
    void send(String label, byte[] v) {
        // a newer speed or incline target replaces the one still waiting
        if (v[0] == 0x02 || v[0] == 0x03) queue.removeIf(c -> c.v[0] == v[0]);
        if (v[0] == 0x02 || v[0] == 0x03) lastCmd = SystemClock.elapsedRealtime();
        queue.add(new Cmd(label, v));
        next();
    }

    @SuppressWarnings("MissingPermission")
    void next() {
        if (pend != null || !setupDone || gatt == null || cpChar == null || queue.isEmpty()) return;
        Cmd c = queue.poll();
        pend = c; pendAt = SystemClock.elapsedRealtime();
        StringBuilder hex = new StringBuilder();
        for (byte x : c.v) hex.append(String.format("%02x ", x & 0xff));
        log("→ " + c.label + " [" + hex.toString().trim() + "]");
        if (c.v[0] == 0x07 || c.v[0] == 0x08) lastOwn = SystemClock.elapsedRealtime();
        boolean ok;
        if (Build.VERSION.SDK_INT >= 33) ok = gatt.writeCharacteristic(cpChar, c.v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0;
        else { cpChar.setValue(c.v); cpChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); ok = gatt.writeCharacteristic(cpChar); }
        if (!ok) { log("✗ " + c.label + " : envoi refusé par Android"); pend = null; h.postDelayed(this::next, 200); return; }
        h.postDelayed(pendTimeout, ANSWER_MS);
    }

    static final String[] RES = {"?", "OK", "non supporté", "paramètre invalide", "échec", "contrôle non autorisé"};

    void onCp(byte[] v) {
        if (v == null || v.length < 3 || (v[0] & 0xff) != 0x80) return;
        int op = v[1] & 0xff, r = v[2] & 0xff;
        String rs = r < RES.length ? RES[r] : "code " + r;
        if (op == 0x00) {
            controlMsg = r == 1 ? " · contrôle accordé" : " · contrôle refusé (" + rs + ")";
            if (conn != READY) { conn = READY; log("✓ prêt : " + (r == 1 ? "contrôle accordé" : "contrôle refusé (" + rs + ")") + " · " + tries + " essai(s)"); tries = 0; afterReady(); }
        }
        if (pend != null && (pend.v[0] & 0xff) == op) {
            h.removeCallbacks(pendTimeout);
            log("← " + pend.label + " : " + rs + " (" + (SystemClock.elapsedRealtime() - pendAt) + " ms)");
            pend = null;
            next();
        }
        ui();
    }

    void afterReady() {
        long t = SystemClock.elapsedRealtime();
        settleAt = t + 3000; z0 = 0;
        // after a reconnection during Avance: send the targets again (if the belt is stopped, the pause is detected 2 s later)
        if (mode == RUN) h.postDelayed(this::sendTargets, 1500);
    }

    // ---------------- measures ----------------
    static int u16(byte[] v, int i) { return (v[i] & 0xff) | ((v[i + 1] & 0xff) << 8); }

    void onData(byte[] v) {
        if (v == null || v.length < 2) return;
        int f = u16(v, 0), i = 2;
        Double sp = null, inc = null;
        try {
            if ((f & 1) == 0) { sp = u16(v, i) / 100.0; i += 2; }
            if ((f & 2) != 0) i += 2;
            if ((f & 4) != 0) i += 3;
            if ((f & 8) != 0 && v.length >= i + 2) inc = ((short) u16(v, i)) / 10.0;
        } catch (Exception e) { return; }
        if (sp == null && inc == null) return;
        long t = SystemClock.elapsedRealtime();
        nData++; lastDataAt = t;
        clone.data(v);
        if (sp != null) mSp = sp;
        if (inc != null) mInc = inc;
        if (mSp == null) return;
        clone.measures(mSp, mInc);
        clone.belt(mSp >= 0.05);
        double s = mSp, in = mInc != null ? mInc : 0;

        // stability of the measures (for adopting a change made on the treadmill and for the targets kept at pause)
        if (Math.abs(s - spRef) >= 0.06) { spRef = s; spSince = t; }
        if (Math.abs(in - incRef) >= 0.3) { incRef = in; incSince = t; }
        boolean spStable = t - spSince >= 2500, incStable = t - incSince >= 2500;
        if (s >= 0.5 && spStable && incStable) { stableSp = s; stableInc = in; }

        boolean mine = t - lastOwn < 3000;
        if (mode == RUN && t > settleAt && !mine) {
            if (s < 0.05) { if (z0 == 0) z0 = t; else if (t - z0 >= 2000) pauseDetected("vitesse nulle"); }
            else {
                z0 = 0;
                if (t - lastCmd > 4000 && pend == null && queue.isEmpty()) {
                    if (spStable && s >= sMin - 0.05 && Math.abs(s - tSp) >= 0.15) { log("vitesse changée sur le tapis : consigne " + f1(tSp) + " → " + f1(s) + " km/h"); tSp = r1(s); }
                    if (incStable && Math.abs(in - tInc) >= 0.6) { log("inclinaison changée sur le tapis : consigne " + f1(tInc) + " → " + f1(in) + " %"); tInc = r1(in); }
                }
            }
        } else z0 = 0;
        if (mode == PAUSE && pausedByTm && s >= 0.5 && !mine) resumeDetected("bande en marche");
        if (mode == STOP && s >= 0.5 && !mine && t > settleAt) {
            mode = RUN; settleAt = t + 3000;
            if (spStable) tSp = r1(s);
            log("▶ bande démarrée sur le tapis → commande Avance");
        }
        ui();
    }

    void onStatus(byte[] v) {
        if (v == null || v.length < 1) return;
        clone.status(v);
        int c = v[0] & 0xff, p = v.length > 1 ? v[1] & 0xff : 0;
        boolean mine = SystemClock.elapsedRealtime() - lastOwn < 3000;
        String m;
        switch (c) {
            case 0x01: m = "réinitialisation"; break;
            case 0x02: m = p == 2 ? "mis en pause" : "arrêté"; break;
            case 0x03: m = "arrêté par la clé de sécurité"; break;
            case 0x04: m = "démarré ou repris"; break;
            case 0x05: m = v.length >= 3 ? "vitesse cible " + f1(u16(v, 1) / 100.0) + " km/h" : "vitesse cible changée"; break;
            case 0x06: m = v.length >= 3 ? "inclinaison cible " + f1(((short) u16(v, 1)) / 10.0) + " %" : "inclinaison cible changée"; break;
            case 0xFF: m = "contrôle repris par le tapis"; break;
            default: m = "code 0x" + Integer.toHexString(c);
        }
        tmMsg = m + (c <= 0x04 ? (mine ? " (suite à l'app)" : " (sur le tapis)") : "");
        if (c != 0x05 && c != 0x06) log("état ← " + tmMsg);
        if ((c == 0x02 || c == 0x03) && !mine) pauseDetected(c == 3 ? "clé de sécurité" : "bouton du tapis");
        else if (c == 0x04 && !mine) resumeDetected("bouton du tapis");
        else if (c == 0xFF) { controlMsg = " · contrôle perdu"; send("Reprise du contrôle", new byte[]{0x00}); }
        ui();
    }

    // ---------------- connection ----------------
    @SuppressWarnings("MissingPermission")
    void connect() {
        if (!hasPermissions()) { askPermissions(); return; }
        if (adapter == null || !adapter.isEnabled()) { want = false; tmMsg = "Active le Bluetooth du téléphone, puis touche Connecter."; ui(); return; }
        want = true; tries = 0;
        log("connexion demandée");
        attempt();
    }

    @SuppressWarnings("MissingPermission")
    void attempt() {
        if (!want) return;
        if (device == null) { scan(); return; }
        tries++;
        conn = CONNECTING; setupDone = false; pend = null; queue.clear(); subs.clear(); cpChar = null; controlMsg = "";
        h.removeCallbacks(pendTimeout);
        stepTo("connexion");
        gatt = device.connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE);
        ui();
    }

    @SuppressWarnings("MissingPermission")
    void scan() {
        conn = SEARCH; ui();
        BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
        if (sc == null) { h.postDelayed(this::attempt, 3000); return; }
        stopScan();
        scanCb = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult r) {
                BluetoothDevice d = r.getDevice();
                String n = d.getName();
                List<ParcelUuid> u = r.getScanRecord() != null ? r.getScanRecord().getServiceUuids() : null;
                boolean ftms = u != null && u.contains(new ParcelUuid(FTMS));
                if (ftms || (n != null && (n.startsWith("EW-TM") || n.startsWith("FT") || n.startsWith("FS")))) {
                    h.post(() -> {
                        if (device != null || !want) return;
                        device = d;
                        stopScan();
                        skylink = n != null && n.startsWith("EW-TM");
                        if (skylink) { sMin = 1; sMax = 13; iMin = -5; iMax = 40; }
                        log("tapis trouvé : " + n + " (" + d.getAddress() + ")" + (skylink ? " · Care Skylink : 1–13 km/h, −5–40 %" : ""));
                        attempt();
                    });
                }
            }
        };
        sc.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb);
        log("recherche du tapis…");
    }

    @SuppressWarnings("MissingPermission")
    void stopScan() {
        if (scanCb == null) return;
        try { adapter.getBluetoothLeScanner().stopScan(scanCb); } catch (Exception ignored) { }
        scanCb = null;
    }

    void stepTo(String s) { step = s; h.removeCallbacks(watchdog); h.postDelayed(watchdog, STEP_TIMEOUT_MS); ui(); }

    void fail(String why) {
        h.removeCallbacks(watchdog);
        boolean wasReady = conn == READY;
        log((wasReady ? "⚠ liaison perdue : " : "✗ essai " + tries + " : ") + why);
        closeGatt();
        if (want) { conn = SEARCH; h.postDelayed(this::attempt, RETRY_MS); } else conn = OFF;
        ui();
    }

    @SuppressWarnings("MissingPermission")
    void closeGatt() {
        h.removeCallbacks(watchdog);
        h.removeCallbacks(pendTimeout);
        h.removeCallbacks(readTimeout);
        setupDone = false; pend = null; queue.clear(); cpChar = null; reads.clear();
        BluetoothGatt g = gatt;
        gatt = null;
        if (g != null) { try { g.disconnect(); } catch (Exception ignored) { } try { g.close(); } catch (Exception ignored) { } }
    }

    void disconnect() {
        want = false;
        h.removeCallbacksAndMessages(null);
        h.post(tick);
        stopScan();
        if (conn == READY && turning()) {
            send("Stop avant déconnexion", new byte[]{0x08, 0x01});
            mode = STOP;
            h.postDelayed(this::finishDisconnect, 1200);
        } else finishDisconnect();
    }

    void finishDisconnect() {
        closeGatt();
        conn = OFF; mSp = null; mInc = null;
        log("déconnecté : tapis libéré");
        ui();
    }

    @SuppressWarnings("MissingPermission")
    void subscribeNext(BluetoothGatt g) {
        if (subs.isEmpty()) {
            BluetoothGattService s = g.getService(FTMS);
            reads.clear();
            for (UUID u : new UUID[]{FEATURE, SPEED_RANGE, INCL_RANGE}) if (s != null && s.getCharacteristic(u) != null) reads.add(s.getCharacteristic(u));
            readNext();
            return;
        }
        BluetoothGattCharacteristic c = subs.get(0);
        String nm = c.getUuid().equals(CP) ? "point de contrôle" : c.getUuid().equals(STATUS) ? "état machine" : "données";
        stepTo("abonnement " + nm);
        g.setCharacteristicNotification(c, true);
        BluetoothGattDescriptor d = c.getDescriptor(CCCD);
        if (d == null) { subs.remove(0); subscribeNext(g); return; }
        byte[] val = c.getUuid().equals(CP) ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        boolean sent;
        if (Build.VERSION.SDK_INT >= 33) sent = g.writeDescriptor(d, val) == 0;
        else { d.setValue(val); sent = g.writeDescriptor(d); }
        if (!sent) fail("abonnement " + nm + " refusé par Android");
    }

    static String rname(UUID u) { return u.equals(FEATURE) ? "fonctions du tapis" : u.equals(SPEED_RANGE) ? "plage de vitesse" : "plage d'inclinaison"; }

    /** Reads the treadmill's features and ranges (for the clone and the limits), then asks for control. */
    @SuppressWarnings("MissingPermission")
    void readNext() {
        h.removeCallbacks(readTimeout);
        if (gatt == null) return;
        if (reads.isEmpty()) {
            setupDone = true;
            stepTo("prise de contrôle");
            queue.addFirst(new Cmd("Prise de contrôle", new byte[]{0x00}));
            next();
            return;
        }
        stepTo("lecture " + rname(reads.get(0).getUuid()));
        if (!gatt.readCharacteristic(reads.get(0))) { reads.remove(0); readNext(); return; }
        h.postDelayed(readTimeout, 3000);
    }

    void onRead(UUID u, byte[] v, int st) {
        if (reads.isEmpty() || !reads.get(0).getUuid().equals(u)) return;
        reads.remove(0);
        if (st == 0 && v != null) {
            if (u.equals(FEATURE)) clone.setFeature(v);
            else if (u.equals(SPEED_RANGE) && v.length >= 4) {
                clone.setSpeedRange(v);
                if (!skylink) { sMin = u16(v, 0) / 100.0; sMax = u16(v, 2) / 100.0; }
                log("plage de vitesse du tapis : " + f1(u16(v, 0) / 100.0) + "–" + f1(u16(v, 2) / 100.0) + " km/h" + (skylink ? " (limites fixes Care Skylink gardées)" : ""));
            } else if (u.equals(INCL_RANGE) && v.length >= 4) {
                clone.setInclRange(v);
                if (!skylink) { iMin = ((short) u16(v, 0)) / 10.0; iMax = ((short) u16(v, 2)) / 10.0; }
                log("plage d'inclinaison du tapis : " + f1(((short) u16(v, 0)) / 10.0) + "–" + f1(((short) u16(v, 2)) / 10.0) + " %" + (skylink ? " (limites fixes Care Skylink gardées)" : ""));
            }
        }
        readNext();
    }

    final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @SuppressWarnings("MissingPermission")
        @Override public void onConnectionStateChange(BluetoothGatt g, int st, int ns) {
            h.post(() -> {
                if (g != gatt) return;
                if (ns == BluetoothProfile.STATE_CONNECTED) { stepTo("découverte des services"); g.discoverServices(); }
                else if (ns == BluetoothProfile.STATE_DISCONNECTED) fail("déconnecté (statut " + st + ")");
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
            h.post(() -> {
                if (g != gatt) return;
                BluetoothGattService s = g.getService(FTMS);
                if (st != 0 || s == null) { fail("service FTMS introuvable (statut " + st + ")"); return; }
                cpChar = s.getCharacteristic(CP);
                if (cpChar == null) { fail("point de contrôle introuvable"); return; }
                subs.clear();
                subs.add(cpChar);
                if (s.getCharacteristic(STATUS) != null) subs.add(s.getCharacteristic(STATUS));
                if (s.getCharacteristic(DATA) != null) subs.add(s.getCharacteristic(DATA));
                subscribeNext(g);
            });
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int st) {
            h.post(() -> {
                if (g != gatt || subs.isEmpty()) return;
                BluetoothGattCharacteristic c = subs.remove(0);
                if (st != 0) { fail("abonnement refusé par le tapis (statut " + st + ")"); return; }
                subscribeNext(g);
            });
        }

        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v, int st) {
            byte[] copy = v == null ? null : v.clone();
            h.post(() -> { if (g == gatt) onRead(c.getUuid(), copy, st); });
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
            if (Build.VERSION.SDK_INT >= 33) return;
            byte[] copy = c.getValue() == null ? null : c.getValue().clone();
            h.post(() -> { if (g == gatt) onRead(c.getUuid(), copy, st); });
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
            h.post(() -> {
                if (g != gatt || st == 0 || pend == null) return;
                h.removeCallbacks(pendTimeout);
                log("✗ " + pend.label + " : écriture refusée (statut " + st + ")");
                pend = null;
                next();
            });
        }

        void changed(BluetoothGatt g, UUID u, byte[] v) {
            byte[] copy = v == null ? null : v.clone();
            h.post(() -> {
                if (g != gatt) return;
                if (u.equals(DATA)) onData(copy);
                else if (u.equals(CP)) { if (conn != READY && step.equals("prise de contrôle")) h.removeCallbacks(watchdog); onCp(copy); }
                else if (u.equals(STATUS)) onStatus(copy);
            });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) { changed(g, c.getUuid(), v); }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT >= 33) return;
            changed(g, c.getUuid(), c.getValue());
        }
    };

    @Override protected void onDestroy() { want = false; stopScan(); closeGatt(); if (clone != null) clone.stop(); super.onDestroy(); }
}
