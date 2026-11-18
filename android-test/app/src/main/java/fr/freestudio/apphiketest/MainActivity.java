package fr.freestudio.apphiketest;

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
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Bluetooth test for the Care Skylink treadmill (FTMS), to decide whether a native App Hike is worth writing.
 * Each attempt: connect, (optionally) request MTU 517, discover services, subscribe control point / machine
 * status / data, ask for control (opcode 00, the belt does not move), then disconnect. Attempts alternate between
 * "MTU 517 requested first" (what native apps usually do) and "no MTU request" (what Chrome does), and the report
 * compares the two success rates.
 */
public class MainActivity extends Activity {
    static final UUID FTMS = uuid16(0x1826), CP = uuid16(0x2AD9), STATUS = uuid16(0x2ADA), DATA = uuid16(0x2ACD), CCCD = uuid16(0x2902);
    static final int ATTEMPTS = 20, STEP_TIMEOUT_MS = 6000, PAUSE_MS = 1500;

    static UUID uuid16(int v) { return UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v)); }

    final Handler h = new Handler(Looper.getMainLooper());
    TextView status, log;
    ScrollView scroll;
    final StringBuilder report = new StringBuilder();
    BluetoothAdapter adapter;
    BluetoothDevice device;
    BluetoothGatt gatt;
    boolean running;
    int attempt, okMtu, okNoMtu, nMtu, nNoMtu;

    // state of the current attempt
    boolean useMtu, finished;
    String step = "";
    long t0;
    final List<String> steps = new ArrayList<>();
    final List<BluetoothGattCharacteristic> subs = new ArrayList<>();
    BluetoothGattCharacteristic cpChar;
    int dataCount;
    final Runnable watchdog = () -> fail("pas de réponse à l'étape « " + step + " » en " + STEP_TIMEOUT_MS / 1000 + " s");

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p * 2, p, p);
        TextView title = new TextView(this);
        title.setText("Test Bluetooth App Hike");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView info = new TextView(this);
        info.setText("Allume le tapis, ferme App Hike et Kinomap, puis lance le test. " + ATTEMPTS + " essais de connexion, en alternant « MTU 517 demandée » et « sans demande de MTU (comme Chrome) ». Seule la prise de contrôle est envoyée : la bande ne bouge pas.");
        info.setPadding(0, p / 2, 0, p / 2);
        root.addView(info);
        LinearLayout row = new LinearLayout(this);
        Button go = new Button(this); go.setText("Lancer");
        Button stop = new Button(this); stop.setText("Arrêter");
        Button copy = new Button(this); copy.setText("Copier");
        Button share = new Button(this); share.setText("Partager");
        row.addView(go); row.addView(stop); row.addView(copy); row.addView(share);
        root.addView(row);
        status = new TextView(this);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setPadding(0, p / 2, 0, p / 2);
        root.addView(status);
        scroll = new ScrollView(this);
        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(11);
        log.setTextIsSelectable(true);
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        go.setOnClickListener(v -> start());
        stop.setOnClickListener(v -> { running = false; closeGatt(); setStatus("Arrêté."); summary(); });
        copy.setOnClickListener(v -> {
            ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("rapport", report.toString()));
            setStatus("Rapport copié.");
        });
        share.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report.toString());
            startActivity(Intent.createChooser(i, "Partager le rapport"));
        });
        adapter = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        askPermissions();
        setStatus("Prêt.");
    }

    void askPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, 1);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    void setStatus(String s) { h.post(() -> status.setText(s)); }

    void line(String s) {
        report.append(s).append('\n');
        h.post(() -> { log.setText(report); scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN)); });
    }

    // ---------- scan ----------
    ScanCallback scanCb;

    @SuppressWarnings("MissingPermission")
    void start() {
        if (running) return;
        if (adapter == null || !adapter.isEnabled()) { setStatus("Active le Bluetooth du téléphone."); return; }
        askPermissions();
        report.setLength(0);
        attempt = okMtu = okNoMtu = nMtu = nNoMtu = 0;
        String now = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(new Date());
        line("Test Bluetooth App Hike · " + now + " · Android " + Build.VERSION.RELEASE + " · " + Build.MODEL);
        running = true;
        if (device != null) { nextAttempt(); return; }
        setStatus("Recherche du tapis…");
        BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
        scanCb = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult r) {
                BluetoothDevice d = r.getDevice();
                String n = d.getName();
                List<ParcelUuid> u = r.getScanRecord() != null ? r.getScanRecord().getServiceUuids() : null;
                boolean ftms = u != null && u.contains(new ParcelUuid(FTMS));
                if (device == null && (ftms || (n != null && (n.startsWith("EW-TM") || n.startsWith("FT") || n.startsWith("FS"))))) {
                    device = d;
                    sc.stopScan(this);
                    line("Tapis trouvé : " + n + " (" + d.getAddress() + ")");
                    h.post(MainActivity.this::nextAttempt);
                }
            }
        };
        sc.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb);
        h.postDelayed(() -> {
            if (device == null && running) { sc.stopScan(scanCb); running = false; setStatus("Aucun tapis trouvé en 15 s : est-il allumé et libre ?"); }
        }, 15000);
    }

    // ---------- one attempt ----------
    @SuppressWarnings("MissingPermission")
    void nextAttempt() {
        if (!running) return;
        if (attempt >= ATTEMPTS) { running = false; summary(); return; }
        attempt++;
        useMtu = attempt % 2 == 1;
        finished = false;
        steps.clear(); subs.clear(); cpChar = null; dataCount = 0;
        setStatus("Essai " + attempt + "/" + ATTEMPTS + (useMtu ? " · MTU 517 demandée" : " · sans demande de MTU"));
        t0 = SystemClock.elapsedRealtime();
        stepTo("connexion");
        gatt = device.connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE);
    }

    void stepTo(String s) {
        step = s;
        h.removeCallbacks(watchdog);
        h.postDelayed(watchdog, STEP_TIMEOUT_MS);
    }

    void mark(String s) { steps.add(String.format(Locale.FRANCE, "   +%5.2f s  %s", (SystemClock.elapsedRealtime() - t0) / 1000.0, s)); }

    void fail(String why) {
        if (finished) return;
        mark("✗ ÉCHEC : " + why);
        end(false);
    }

    void end(boolean ok) {
        if (finished) return;
        finished = true;
        h.removeCallbacks(watchdog);
        if (useMtu) { nMtu++; if (ok) okMtu++; } else { nNoMtu++; if (ok) okNoMtu++; }
        line(String.format(Locale.FRANCE, "Essai %d · %s · %s en %.1f s", attempt, useMtu ? "MTU 517 demandée" : "sans demande de MTU", ok ? "RÉUSSI" : "ÉCHEC", (SystemClock.elapsedRealtime() - t0) / 1000.0));
        for (String s : steps) line(s);
        closeGatt();
        h.postDelayed(this::nextAttempt, PAUSE_MS);
    }

    @SuppressWarnings("MissingPermission")
    void closeGatt() {
        h.removeCallbacks(watchdog);
        BluetoothGatt g = gatt;
        gatt = null;
        if (g != null) { try { g.disconnect(); } catch (Exception ignored) { } try { g.close(); } catch (Exception ignored) { } }
    }

    void summary() {
        line("");
        line(String.format(Locale.FRANCE, "BILAN · MTU 517 demandée : %d/%d réussis · sans demande de MTU : %d/%d réussis", okMtu, nMtu, okNoMtu, nNoMtu));
        setStatus(String.format(Locale.FRANCE, "Terminé · avec MTU %d/%d · sans MTU %d/%d", okMtu, nMtu, okNoMtu, nNoMtu));
    }

    @SuppressWarnings("MissingPermission")
    void subscribeNext(BluetoothGatt g) {
        if (subs.isEmpty()) {
            // ask for control: opcode 00; success = indication 80 00 01 on the control point
            stepTo("prise de contrôle");
            byte[] v = {0x00};
            boolean sent;
            if (Build.VERSION.SDK_INT >= 33) sent = g.writeCharacteristic(cpChar, v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0;
            else { cpChar.setValue(v); cpChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); sent = g.writeCharacteristic(cpChar); }
            if (!sent) fail("envoi de la prise de contrôle refusé par Android");
            return;
        }
        BluetoothGattCharacteristic c = subs.get(0);
        String nm = c.getUuid().equals(CP) ? "point de contrôle" : c.getUuid().equals(STATUS) ? "état machine" : "données";
        stepTo("abonnement " + nm);
        g.setCharacteristicNotification(c, true);
        BluetoothGattDescriptor d = c.getDescriptor(CCCD);
        if (d == null) { mark("pas de descripteur pour " + nm + ", étape sautée"); subs.remove(0); subscribeNext(g); return; }
        byte[] val = c.getUuid().equals(CP) ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        boolean sent;
        if (Build.VERSION.SDK_INT >= 33) sent = g.writeDescriptor(d, val) == 0;
        else { d.setValue(val); sent = g.writeDescriptor(d); }
        if (!sent) fail("abonnement " + nm + " refusé par Android");
    }

    final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @SuppressWarnings("MissingPermission")
        @Override public void onConnectionStateChange(BluetoothGatt g, int st, int ns) {
            h.post(() -> {
                if (g != gatt || finished) return;
                if (ns == BluetoothProfile.STATE_CONNECTED) {
                    mark("connecté (statut " + st + ")");
                    if (useMtu) { stepTo("négociation MTU"); if (!g.requestMtu(517)) fail("demande de MTU refusée par Android"); }
                    else { stepTo("découverte des services"); g.discoverServices(); }
                } else if (ns == BluetoothProfile.STATE_DISCONNECTED) {
                    fail("déconnecté par le tapis ou Android (statut " + st + ")");
                }
            });
        }

        @SuppressWarnings("MissingPermission")
        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int st) {
            h.post(() -> {
                if (g != gatt || finished) return;
                mark("MTU " + mtu + " (statut " + st + ")" + (useMtu && step.equals("négociation MTU") ? "" : " · négociée par le tapis"));
                if (useMtu && step.equals("négociation MTU")) { stepTo("découverte des services"); g.discoverServices(); }
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
            h.post(() -> {
                if (g != gatt || finished) return;
                BluetoothGattService s = g.getService(FTMS);
                if (st != 0 || s == null) { fail("service FTMS introuvable (statut " + st + ")"); return; }
                mark("services découverts");
                cpChar = s.getCharacteristic(CP);
                if (cpChar == null) { fail("point de contrôle introuvable"); return; }
                subs.add(cpChar);
                if (s.getCharacteristic(STATUS) != null) subs.add(s.getCharacteristic(STATUS));
                if (s.getCharacteristic(DATA) != null) subs.add(s.getCharacteristic(DATA));
                subscribeNext(g);
            });
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int st) {
            h.post(() -> {
                if (g != gatt || finished || subs.isEmpty()) return;
                BluetoothGattCharacteristic c = subs.remove(0);
                String nm = c.getUuid().equals(CP) ? "point de contrôle" : c.getUuid().equals(STATUS) ? "état machine" : "données";
                if (st != 0) { fail("abonnement " + nm + " refusé par le tapis (statut " + st + ")"); return; }
                mark("abonné : " + nm);
                subscribeNext(g);
            });
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
            h.post(() -> { if (g == gatt && !finished) mark("prise de contrôle envoyée (statut " + st + ")"); });
        }

        void changed(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) {
            h.post(() -> {
                if (g != gatt || finished) return;
                if (c.getUuid().equals(DATA)) { dataCount++; return; }
                if (c.getUuid().equals(CP) && v != null && v.length >= 3 && (v[0] & 0xff) == 0x80 && v[1] == 0x00) {
                    mark("réponse du tapis : " + (v[2] == 1 ? "contrôle accordé" : "code " + v[2]));
                    stepTo("réception des données");
                    h.postDelayed(() -> {
                        if (g != gatt || finished) return;
                        mark(dataCount + " paquets de données reçus en 1 s");
                        end(true);
                    }, 1000);
                }
            });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) { changed(g, c, v); }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) { changed(g, c, c.getValue()); }
    };

    @Override protected void onDestroy() { running = false; closeGatt(); super.onDestroy(); }
}
