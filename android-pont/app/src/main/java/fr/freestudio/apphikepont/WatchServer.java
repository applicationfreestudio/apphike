package fr.freestudio.apphikepont;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bluetooth server for the watch: the phone presents itself as one or more sensors (foot pod RSC, FTMS treadmill,
 * bike speed sensor CSC) and sends the treadmill speed and incline (or test values). Every way of doing it is an
 * option, to find what the watch accepts; everything the watch does is logged. A heart-rate sensor profile is also
 * available (belt value or test value): every watch accepts that type, which shows whether it sees the phone at all.
 */
@SuppressWarnings("MissingPermission")
public class WatchServer {
    interface Host { void log(String s); void changed(); boolean isCentral(String address); void pairing(boolean on, String who); }

    static UUID u16(int v) { return UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", v)); }
    static final UUID CCCD = u16(0x2902),
            RSC = u16(0x1814), RSC_MEAS = u16(0x2A53), RSC_FEAT = u16(0x2A54), SC_CP = u16(0x2A55), LOCATION = u16(0x2A5D),
            CSC = u16(0x1816), CSC_MEAS = u16(0x2A5B), CSC_FEAT = u16(0x2A5C),
            FTMS = u16(0x1826), FT_FEAT = u16(0x2ACC), FT_DATA = u16(0x2ACD), FT_TRAIN = u16(0x2AD3), FT_SPR = u16(0x2AD4), FT_INR = u16(0x2AD5), FT_CP = u16(0x2AD9), FT_ST = u16(0x2ADA),
            DIS = u16(0x180A), MANUF = u16(0x2A29), MODEL = u16(0x2A24), SERIAL = u16(0x2A25), FW = u16(0x2A26), HW = u16(0x2A27), SW = u16(0x2A28),
            BAS = u16(0x180F), BATT = u16(0x2A19),
            HRS = u16(0x180D), HRM = u16(0x2A37), BSL = u16(0x2A38);

    static String hex4(UUID u) { return String.format("0x%04X", (int) (u.getMostSignificantBits() >>> 32) & 0xffff); }
    static String nameOf(UUID u) {
        Map<UUID, String> m = new HashMap<>();
        m.put(RSC_MEAS, "foulée : mesure"); m.put(RSC_FEAT, "foulée : fonctions"); m.put(SC_CP, "foulée : point de contrôle"); m.put(LOCATION, "position du capteur");
        m.put(CSC_MEAS, "vélo : mesure"); m.put(CSC_FEAT, "vélo : fonctions"); m.put(FT_FEAT, "tapis : fonctions"); m.put(FT_DATA, "tapis : données");
        m.put(FT_TRAIN, "tapis : état d'entraînement"); m.put(FT_SPR, "tapis : plage de vitesse"); m.put(FT_INR, "tapis : plage d'inclinaison"); m.put(FT_CP, "tapis : point de contrôle");
        m.put(FT_ST, "tapis : état machine"); m.put(MANUF, "fabricant"); m.put(MODEL, "modèle"); m.put(SERIAL, "n° de série"); m.put(FW, "micrologiciel");
        m.put(HW, "matériel"); m.put(SW, "logiciel"); m.put(BATT, "batterie"); m.put(HRM, "cardio : mesure"); m.put(BSL, "cardio : position");
        String n = m.get(u);
        return hex4(u) + (n != null ? " (" + n + ")" : "");
    }

    /** Default options (also the list of what can be tried). */
    static JSONObject defaults() {
        try {
            return new JSONObject()
                    .put("src", "tapis").put("tSp", 5.0).put("tInc", 8.0)
                    .put("tHr", 120).put("hrContact", true).put("rsc", false).put("hr", true).put("ftms", true).put("csc", false).put("dis", true).put("bas", true)
                    .put("name", "").put("advFirst", "tapis").put("nameMain", true).put("advAll", true).put("txHigh", true).put("advFast", true)
                    .put("secure", false).put("period", 1000)
                    .put("cadMode", "estimee").put("cadFix", 160).put("cadHalf", false).put("stride", true).put("dist", true).put("running", "auto").put("loc", 2).put("scCp", false)
                    .put("ftFields", "vitesse+inclinaison").put("ftCp", true).put("ftRanges", true).put("ftTrain", true)
                    .put("wheel", 2105);
        } catch (Exception e) { return new JSONObject(); }
    }

    final Context ctx;
    final Host host;
    final Handler h = new Handler(Looper.getMainLooper());
    JSONObject o = defaults();
    BluetoothManager bm;
    BluetoothGattServer server;
    BluetoothLeAdvertiser adv;
    boolean on, advertising, nameInMain;
    String error = "", oldName;
    final Map<String, BluetoothDevice> clients = new HashMap<>();
    final Map<String, Set<UUID>> subs = new HashMap<>();
    final Map<String, Integer> mtu = new HashMap<>();
    final ArrayDeque<BluetoothGattService> toAdd = new ArrayDeque<>();
    long sent, sentFail, startAt, lastTick;
    // values
    Double tmSp, tmInc; long tmAt;
    double dist, elev, wheelRevs; long lastWheelEvt;
    double curSp, curInc; int curCad, curHr;

    WatchServer(Context c, Host h) { ctx = c; host = h; }

    void setOptions(JSONObject n) {
        JSONObject d = defaults();
        try { for (java.util.Iterator<String> it = n.keys(); it.hasNext(); ) { String k = it.next(); d.put(k, n.get(k)); } } catch (Exception ignored) { }
        boolean gatt = on && changed(o, d, "secure"), ad = on && changed(o, d, "rsc", "hr", "ftms", "csc", "advFirst", "name", "nameMain", "advAll", "txHigh", "advFast");
        o = d;
        if (gatt) { host.log("montre : chiffrement changé, capteur reconstruit (refais l'association sur la montre)"); stop(); h.postDelayed(this::start, 600); }
        else if (ad) { host.log("montre : annonce mise à jour (le capteur garde la même structure)"); restartAdvertising(); }
        host.changed();
    }

    static boolean changed(JSONObject a, JSONObject b, String... keys) {
        for (String k : keys) if (!String.valueOf(a.opt(k)).equals(String.valueOf(b.opt(k)))) return true;
        return false;
    }

    void restartAdvertising() {
        try { if (adv != null) adv.stopAdvertising(advCb); } catch (Exception ignored) { }
        advertising = false;
        applyName();
        h.postDelayed(() -> { if (on) { nameInMain = o.optBoolean("nameMain", true); advertise(); } }, 300);
    }

    /** Custom Bluetooth name of the phone during the emission (restored at stop). */
    void applyName() {
        BluetoothAdapter a = bm.getAdapter();
        String nm = o.optString("name", "").trim();
        if (nm.isEmpty()) { if (oldName != null) { try { a.setName(oldName); host.log("montre : nom Bluetooth rétabli « " + oldName + " »"); } catch (Exception ignored) { } oldName = null; } return; }
        if (nm.equals(a.getName())) return;
        if (oldName == null) oldName = a.getName();
        boolean ok = false;
        try { ok = a.setName(nm); } catch (Exception e) { host.log("montre : changement de nom refusé (" + e.getMessage() + ")"); }
        host.log("montre : nom Bluetooth du téléphone « " + nm + " » " + (ok ? "appliqué (rétabli à l'arrêt)" : "refusé par Android"));
    }

    Integer beltHr; long hrAt;
    void heartRate(int b) { beltHr = b; hrAt = SystemClock.elapsedRealtime(); }

    void treadmill(Double sp, Double inc) { if (sp != null) tmSp = sp; if (inc != null) tmInc = inc; tmAt = SystemClock.elapsedRealtime(); }

    // ------------------------------------------------------------ start / stop
    final BroadcastReceiver bond = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
            if (d == null) return;
            host.log("montre : appairage avec " + label(d) + " → " + (st == BluetoothDevice.BOND_BONDED ? "appairé" : st == BluetoothDevice.BOND_BONDING ? "en cours : accepte la demande d'association sur le téléphone" : "non appairé"));
            host.pairing(st == BluetoothDevice.BOND_BONDING, label(d));
        }
    };

    void start() {
        if (on) return;
        bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter a = bm.getAdapter();
        if (a == null || !a.isEnabled()) { error = "Bluetooth désactivé"; host.changed(); return; }
        if (!a.isMultipleAdvertisementSupported() || a.getBluetoothLeAdvertiser() == null) { error = "ce téléphone ne sait pas émettre"; host.log("✗ montre : " + error); host.changed(); return; }
        if (!o.optBoolean("rsc") && !o.optBoolean("ftms") && !o.optBoolean("csc") && !o.optBoolean("hr")) { error = "aucun profil coché"; host.changed(); return; }
        on = true; error = ""; sent = 0; sentFail = 0; startAt = SystemClock.elapsedRealtime(); dist = 0; elev = 0; wheelRevs = 0;
        try { ctx.registerReceiver(bond, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)); } catch (Exception ignored) { }
        applyName();
        server = bm.openGattServer(ctx, cb);
        if (server == null) { on = false; error = "serveur Bluetooth impossible à ouvrir"; host.log("✗ montre : " + error); host.changed(); return; }
        toAdd.clear();
        boolean sec = o.optBoolean("secure");
        // Fixed structure (all services, same order, same characteristics), whatever the options: a watch that has
        // memorised the sensor finds it identical at every start. The options only change the advertising and the values sent.
        BluetoothGattService hr = new BluetoothGattService(HRS, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        hr.addCharacteristic(chr(HRM, P_NOTIFY, sec));
        hr.addCharacteristic(chr(BSL, P_READ, sec));
        toAdd.add(hr);
        BluetoothGattService rs = new BluetoothGattService(RSC, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        rs.addCharacteristic(chr(RSC_MEAS, P_NOTIFY, sec));
        rs.addCharacteristic(chr(RSC_FEAT, P_READ, sec));
        rs.addCharacteristic(chr(LOCATION, P_READ, sec));
        rs.addCharacteristic(chr(SC_CP, P_WRITE | P_INDICATE, sec));
        toAdd.add(rs);
        BluetoothGattService cs = new BluetoothGattService(CSC, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        cs.addCharacteristic(chr(CSC_MEAS, P_NOTIFY, sec));
        cs.addCharacteristic(chr(CSC_FEAT, P_READ, sec));
        cs.addCharacteristic(chr(LOCATION, P_READ, sec));
        toAdd.add(cs);
        BluetoothGattService ft = new BluetoothGattService(FTMS, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        ft.addCharacteristic(chr(FT_FEAT, P_READ, sec));
        ft.addCharacteristic(chr(FT_DATA, P_NOTIFY, sec));
        ft.addCharacteristic(chr(FT_TRAIN, P_READ | P_NOTIFY, sec));
        ft.addCharacteristic(chr(FT_SPR, P_READ, sec));
        ft.addCharacteristic(chr(FT_INR, P_READ, sec));
        ft.addCharacteristic(chr(FT_CP, P_WRITE | P_INDICATE, sec));
        ft.addCharacteristic(chr(FT_ST, P_NOTIFY, sec));
        toAdd.add(ft);
        BluetoothGattService di = new BluetoothGattService(DIS, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        for (UUID u : new UUID[]{MANUF, MODEL, SERIAL, FW, HW, SW}) di.addCharacteristic(chr(u, P_READ, false));
        toAdd.add(di);
        BluetoothGattService ba = new BluetoothGattService(BAS, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        ba.addCharacteristic(chr(BATT, P_READ | P_NOTIFY, false));
        toAdd.add(ba);
        nameInMain = o.optBoolean("nameMain", true);
        host.log("montre : démarrage — profils annoncés : " + profiles() + (sec ? " · chiffrement exigé" : ""));
        server.addService(toAdd.poll());
        host.changed();
    }

    String profiles() {
        List<String> p = new ArrayList<>();
        if (o.optBoolean("rsc")) p.add("foulée (RSC)");
        if (o.optBoolean("csc")) p.add("vélo (CSC)");
        if (o.optBoolean("hr")) p.add("cardio (HRS)");
        if (o.optBoolean("ftms")) p.add("tapis (FTMS)");

        return String.join(", ", p);
    }

    static final int P_READ = BluetoothGattCharacteristic.PROPERTY_READ, P_NOTIFY = BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            P_INDICATE = BluetoothGattCharacteristic.PROPERTY_INDICATE, P_WRITE = BluetoothGattCharacteristic.PROPERTY_WRITE;

    BluetoothGattCharacteristic chr(UUID u, int props, boolean sec) {
        int perms = 0;
        if ((props & P_READ) != 0) perms |= sec ? BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED : BluetoothGattCharacteristic.PERMISSION_READ;
        if ((props & P_WRITE) != 0) perms |= sec ? BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED : BluetoothGattCharacteristic.PERMISSION_WRITE;
        BluetoothGattCharacteristic c = new BluetoothGattCharacteristic(u, props, perms);
        if ((props & (P_NOTIFY | P_INDICATE)) != 0)
            c.addDescriptor(new BluetoothGattDescriptor(CCCD, sec ? BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED | BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
                    : BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        return c;
    }

    final AdvertiseCallback advCb = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings s) {
            h.post(() -> { advertising = true; error = ""; host.log("montre : émission en cours sous le nom « " + phoneName() + " »" + (nameInMain ? "" : " (nom dans la réponse au scan)")); host.changed(); });
        }
        @Override public void onStartFailure(int code) {
            h.post(() -> {
                if (code == ADVERTISE_FAILED_DATA_TOO_LARGE && nameInMain && on) { nameInMain = false; host.log("montre : annonce trop longue, nom déplacé dans la réponse au scan"); advertise(); return; }
                advertising = false;
                error = "émission refusée (code " + code + (code == ADVERTISE_FAILED_DATA_TOO_LARGE ? " : annonce trop longue, décoche des profils ou « tous les profils dans l'annonce »" : "") + ")";
                host.log("✗ montre : " + error); host.changed();
            });
        }
    };

    String phoneName() { try { String n = bm.getAdapter().getName(); return n != null ? n : "téléphone"; } catch (Exception e) { return "téléphone"; } }

    void advertise() {
        adv = bm.getAdapter().getBluetoothLeAdvertiser();
        if (adv == null) { error = "émetteur indisponible"; host.changed(); return; }
        AdvertiseSettings set = new AdvertiseSettings.Builder()
                .setAdvertiseMode(o.optBoolean("advFast", true) ? AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY : AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(o.optBoolean("txHigh", true) ? AdvertiseSettings.ADVERTISE_TX_POWER_HIGH : AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true).setTimeout(0).build();
        AdvertiseData.Builder d = new AdvertiseData.Builder().setIncludeDeviceName(nameInMain);
        // advertising order: the chosen profile first, then the others (tapis, cardio, foulée, vélo)
        List<UUID> list = new ArrayList<>();
        String first = o.optString("advFirst", "tapis");
        String[] order = {first, "tapis", "cardio", "foulee", "velo"};
        for (String k : order) {
            UUID u = k.equals("tapis") && o.optBoolean("ftms") ? FTMS : k.equals("cardio") && o.optBoolean("hr") ? HRS
                    : k.equals("foulee") && o.optBoolean("rsc") ? RSC : k.equals("velo") && o.optBoolean("csc") ? CSC : null;
            if (u != null && !list.contains(u)) list.add(u);
        }
        if (!o.optBoolean("advAll", true) && list.size() > 1) list = list.subList(0, 1);
        for (UUID u : list) d.addServiceUuid(new ParcelUuid(u));
        List<String> names = new ArrayList<>();
        for (UUID u : list) names.add(u.equals(FTMS) ? "tapis" : u.equals(HRS) ? "cardio" : u.equals(RSC) ? "foulée" : "vélo");
        host.log("montre : ordre d'annonce : " + String.join(" → ", names));
        if (list.contains(FTMS)) d.addServiceData(new ParcelUuid(FTMS), new byte[]{0x01, 0x01, 0x00});
        AdvertiseData resp = new AdvertiseData.Builder().setIncludeDeviceName(!nameInMain).build();
        adv.startAdvertising(set, d.build(), resp, advCb);
    }

    void stop() {
        if (!on) return;
        on = false; advertising = false;
        h.removeCallbacks(tick);
        try { if (adv != null) adv.stopAdvertising(advCb); } catch (Exception ignored) { }
        if (server != null) {
            for (BluetoothDevice d : clients.values()) { try { server.cancelConnection(d); } catch (Exception ignored) { } }
            try { server.close(); } catch (Exception ignored) { }
        }
        server = null; clients.clear(); subs.clear(); mtu.clear();
        try { ctx.unregisterReceiver(bond); } catch (Exception ignored) { }
        if (oldName != null) { try { bm.getAdapter().setName(oldName); host.log("montre : nom Bluetooth rétabli « " + oldName + " »"); } catch (Exception ignored) { } oldName = null; }
        host.log("montre : émission arrêtée");
        host.changed();
    }

    // ------------------------------------------------------------ values sent
    final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!on) return;
            send();
            h.postDelayed(this, Math.max(200, o.optInt("period", 1000)));
        }
    };

    boolean live() { return "test".equals(o.optString("src")) || (tmSp != null && SystemClock.elapsedRealtime() - tmAt < 3000); }

    void send() {
        long now = SystemClock.elapsedRealtime();
        if (o.optBoolean("hr")) {
            boolean t = "test".equals(o.optString("src"));
            Integer b = t ? Integer.valueOf(o.optInt("tHr", 120)) : (beltHr != null && now - hrAt < 5000 ? beltHr : null);
            // flags 0x06: sensor contact supported and detected (some watches ignore a value without contact)
            if (b != null) { curHr = b; notifyAll(HRS, HRM, new byte[]{(byte) (o.optBoolean("hrContact", true) ? 0x06 : 0x00), (byte) Math.min(255, Math.max(0, b))}, false); }
        }
        double dt = lastTick == 0 ? 0 : (now - lastTick) / 1000.0;
        lastTick = now;
        boolean test = "test".equals(o.optString("src"));
        if (!live()) return;
        double sp = test ? o.optDouble("tSp", 5) : tmSp, inc = test ? o.optDouble("tInc", 0) : (tmInc != null ? tmInc : 0);
        curSp = sp; curInc = inc;
        double ms = sp / 3.6;
        dist += ms * dt;
        if (inc > 0) elev += ms * dt * inc / 100.0;
        // cadence
        String cm = o.optString("cadMode", "estimee");
        int cad = cm.equals("zero") ? 0 : cm.equals("fixe") ? o.optInt("cadFix", 160) : sp < 0.3 ? 0 : (int) Math.round(sp < 7.5 ? 95 + 4.5 * sp : 150 + 2.5 * sp);
        if (o.optBoolean("cadHalf")) cad = (int) Math.round(cad / 2.0);
        curCad = cad;
        if (o.optBoolean("rsc")) {
            boolean stride = o.optBoolean("stride", true), d = o.optBoolean("dist", true);
            String rm = o.optString("running", "auto");
            boolean running = rm.equals("oui") || (rm.equals("auto") && sp >= 7.5);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write((stride ? 1 : 0) | (d ? 2 : 0) | (running ? 4 : 0));
            int v = (int) Math.round(ms * 256); b.write(v); b.write(v >> 8);
            b.write(Math.min(255, Math.max(0, cad)));
            if (stride) { int sl = cad > 0 ? (int) Math.round(ms * 60 / cad * 100) : 0; b.write(sl); b.write(sl >> 8); }
            if (d) { long dd = Math.round(dist * 10); for (int i = 0; i < 4; i++) b.write((int) (dd >> (8 * i))); }
            notifyAll(RSC, RSC_MEAS, b.toByteArray(), false);
        }
        if (o.optBoolean("csc")) {
            double circ = o.optInt("wheel", 2105) / 1000.0;
            wheelRevs += ms * dt / circ;
            if (ms > 0.05) lastWheelEvt = (lastWheelEvt + Math.round(dt * 1024)) & 0xffff;
            long r = (long) wheelRevs;
            notifyAll(CSC, CSC_MEAS, new byte[]{0x01, (byte) r, (byte) (r >> 8), (byte) (r >> 16), (byte) (r >> 24), (byte) lastWheelEvt, (byte) (lastWheelEvt >> 8)}, false);
        }
        if (o.optBoolean("ftms")) {
            String f = o.optString("ftFields", "vitesse+inclinaison");
            boolean fi = !f.equals("vitesse"), fd = f.contains("distance") || f.equals("complet"), fe = f.equals("complet"), ft = f.equals("complet");
            int flags = (fd ? 0x04 : 0) | (fi ? 0x08 : 0) | (fe ? 0x10 : 0) | (ft ? 0x400 : 0);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(flags); b.write(flags >> 8);
            int s = (int) Math.round(sp * 100); b.write(s); b.write(s >> 8);
            if (fd) { int dd = (int) Math.round(dist); b.write(dd); b.write(dd >> 8); b.write(dd >> 16); }
            if (fi) { int i = (int) Math.round(inc * 10), ra = (int) Math.round(Math.toDegrees(Math.atan(inc / 100)) * 10); b.write(i); b.write(i >> 8); b.write(ra); b.write(ra >> 8); }
            if (fe) { int e = (int) Math.round(elev * 10); b.write(e); b.write(e >> 8); b.write(0); b.write(0); }
            if (ft) { int t = (int) ((now - startAt) / 1000); b.write(t); b.write(t >> 8); }
            notifyAll(FTMS, FT_DATA, b.toByteArray(), false);
        }
    }

    byte[] ftFeature() {
        String f = o.optString("ftFields", "vitesse+inclinaison");
        int feat = (f.contains("distance") || f.equals("complet") ? 0x04 : 0) | (!f.equals("vitesse") ? 0x08 : 0) | (f.equals("complet") ? 0x10 | 0x1000 : 0);
        int tgt = o.optBoolean("ftCp") ? 0x03 : 0;
        return new byte[]{(byte) feat, (byte) (feat >> 8), 0, 0, (byte) tgt, 0, 0, 0};
    }

    void notifyAll(UUID svc, UUID ch, byte[] v, boolean ind) {
        if (server == null) return;
        BluetoothGattService s = server.getService(svc);
        BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(ch);
        if (c == null) return;
        for (Map.Entry<String, BluetoothDevice> e : clients.entrySet()) {
            Set<UUID> sub = subs.get(e.getKey());
            if (sub == null || !sub.contains(ch)) continue;
            boolean ok;
            try {
                if (Build.VERSION.SDK_INT >= 33) ok = server.notifyCharacteristicChanged(e.getValue(), c, ind, v) == 0;
                else { c.setValue(v); ok = server.notifyCharacteristicChanged(e.getValue(), c, ind); }
            } catch (Exception x) { ok = false; }
            if (ok) { sent++; if (sent % 30 == 0) host.log("montre : " + sent + " envois (dernier " + nameOf(ch) + " [" + hex(v) + "])"); if (sent == 1) host.log("montre : premières données envoyées à " + label(e.getValue()) + " " + nameOf(ch) + " [" + hex(v) + "]"); }
            else if (++sentFail % 20 == 1) host.log("montre : envoi refusé par Android (" + sentFail + " fois)");
        }
    }

    static String hex(byte[] v) { StringBuilder b = new StringBuilder(); for (byte x : v) b.append(String.format("%02x ", x & 0xff)); return b.toString().trim(); }

    String label(BluetoothDevice d) { String n = null; try { n = d.getName(); } catch (Exception ignored) { } return (n != null ? n : "appareil") + " " + d.getAddress(); }

    byte[] readValue(UUID u) {
        if (u.equals(RSC_FEAT)) { int f = (o.optBoolean("stride", true) ? 1 : 0) | (o.optBoolean("dist", true) ? 2 : 0) | 4; return new byte[]{(byte) f, 0}; }
        if (u.equals(CSC_FEAT)) return new byte[]{0x01, 0x00};
        if (u.equals(LOCATION)) return new byte[]{(byte) o.optInt("loc", 2)};
        if (u.equals(FT_FEAT)) return ftFeature();
        if (u.equals(FT_TRAIN)) return new byte[]{0x00, (byte) (curSp > 0.05 ? 0x0D : 0x01)};
        if (u.equals(FT_SPR)) return new byte[]{100, 0, 0x40, 0x06, 10, 0};
        if (u.equals(FT_INR)) return new byte[]{(byte) 0xCE, (byte) 0xFF, (byte) 0x90, 0x01, 10, 0};
        if (u.equals(MANUF)) return "App Hike".getBytes(StandardCharsets.UTF_8);
        if (u.equals(MODEL)) return "App Hike Sensor".getBytes(StandardCharsets.UTF_8);
        if (u.equals(SERIAL)) return "AH0001".getBytes(StandardCharsets.UTF_8);
        if (u.equals(FW) || u.equals(SW)) return "1.0".getBytes(StandardCharsets.UTF_8);
        if (u.equals(HW)) return "1".getBytes(StandardCharsets.UTF_8);
        if (u.equals(BATT)) return new byte[]{100};
        if (u.equals(BSL)) return new byte[]{1};   // chest
        return new byte[0];
    }

    final BluetoothGattServerCallback cb = new BluetoothGattServerCallback() {
        @Override public void onServiceAdded(int st, BluetoothGattService s) {
            h.post(() -> {
                if (!on) return;
                if (st != 0) { host.log("✗ montre : service " + hex4(s.getUuid()) + " refusé (statut " + st + ")"); }
                if (!toAdd.isEmpty()) server.addService(toAdd.poll());
                else { advertise(); lastTick = 0; h.removeCallbacks(tick); h.postDelayed(tick, 500); }
            });
        }

        @Override public void onConnectionStateChange(BluetoothDevice d, int st, int ns) {
            h.post(() -> {
                if (!on) return;
                // Android reports here every Bluetooth link of the phone: the treadmill and the belt (driven by this app)
                // are not clients of the server; a watch already paired with the phone keeps its link with its own app.
                if (host.isCentral(d.getAddress())) return;
                boolean bonded = "oui".equals(bondTxt(d));
                if (ns == BluetoothProfile.STATE_CONNECTED) { clients.put(d.getAddress(), d); host.log("montre : liaison " + label(d) + " ouverte" + (bonded ? " (appareil appairé au téléphone : peut être la liaison de son app, pas une connexion au capteur)" : "")); }
                else if (ns == BluetoothProfile.STATE_DISCONNECTED) { clients.remove(d.getAddress()); subs.remove(d.getAddress()); host.log("montre : liaison " + label(d) + " fermée"); }
                host.changed();
            });
        }

        @Override public void onMtuChanged(BluetoothDevice d, int m) { h.post(() -> { mtu.put(d.getAddress(), m); host.log("montre : " + label(d) + " MTU " + m); }); }

        @Override public void onCharacteristicReadRequest(BluetoothDevice d, int id, int off, BluetoothGattCharacteristic c) {
            byte[] v = readValue(c.getUuid());
            byte[] part = off >= v.length ? new byte[0] : Arrays.copyOfRange(v, off, v.length);
            try { server.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, off, part); } catch (Exception ignored) { }
            if (off == 0) h.post(() -> host.log("montre : lecture " + nameOf(c.getUuid()) + " → [" + hex(v) + "]"));
        }

        @Override public void onDescriptorReadRequest(BluetoothDevice d, int id, int off, BluetoothGattDescriptor de) {
            Set<UUID> s = subs.get(d.getAddress());
            boolean en = s != null && s.contains(de.getCharacteristic().getUuid());
            try { server.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, 0, en ? new byte[]{1, 0} : new byte[]{0, 0}); } catch (Exception ignored) { }
        }

        @Override public void onDescriptorWriteRequest(BluetoothDevice d, int id, BluetoothGattDescriptor de, boolean prep, boolean resp, int off, byte[] v) {
            UUID u = de.getCharacteristic().getUuid();
            boolean en = v != null && v.length > 0 && (v[0] & 3) != 0;
            if (resp) try { server.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, 0, v); } catch (Exception ignored) { }
            h.post(() -> {
                Set<UUID> s = subs.get(d.getAddress());
                if (s == null) { s = new HashSet<>(); subs.put(d.getAddress(), s); }
                if (en) s.add(u); else s.remove(u);
                clients.put(d.getAddress(), d);
                host.log("montre : " + label(d) + (en ? " S'ABONNE à " : " se désabonne de ") + nameOf(u));
                host.changed();
            });
        }

        @Override public void onCharacteristicWriteRequest(BluetoothDevice d, int id, BluetoothGattCharacteristic c, boolean prep, boolean resp, int off, byte[] v) {
            if (resp) try { server.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, 0, null); } catch (Exception ignored) { }
            if (v == null || v.length == 0) return;
            int op = v[0] & 0xff;
            UUID u = c.getUuid();
            h.post(() -> {
                host.log("montre : écriture " + nameOf(u) + " [" + hex(v) + "]");
                // control points: accepted (control, reset, calibration…); treadmill commands are not passed on
                byte code = u.equals(FT_CP) ? (byte) (op <= 0x01 ? 0x01 : 0x02) : (byte) 0x01;
                byte[] ans = u.equals(SC_CP) ? new byte[]{0x10, (byte) op, code} : new byte[]{(byte) 0x80, (byte) op, code};
                h.postDelayed(() -> { try {
                    if (Build.VERSION.SDK_INT >= 33) server.notifyCharacteristicChanged(d, c, true, ans);
                    else { c.setValue(ans); server.notifyCharacteristicChanged(d, c, true); }
                } catch (Exception ignored) { } }, 30);
            });
        }

        @Override public void onNotificationSent(BluetoothDevice d, int st) { if (st != 0) h.post(() -> host.log("montre : notification non reçue par " + label(d) + " (statut " + st + ")")); }
    };

    static String bondTxt(BluetoothDevice d) {
        try { int b = d.getBondState(); return b == BluetoothDevice.BOND_BONDED ? "oui" : b == BluetoothDevice.BOND_BONDING ? "en cours" : "non"; } catch (Exception e) { return "?"; }
    }

    JSONObject state() {
        JSONObject s = new JSONObject();
        try {
            JSONArray cl = new JSONArray();
            for (Map.Entry<String, BluetoothDevice> e : clients.entrySet()) {
                Set<UUID> sub = subs.get(e.getKey());
                List<String> l = new ArrayList<>();
                if (sub != null) for (UUID u : sub) l.add(nameOf(u));
                cl.put(new JSONObject().put("name", label(e.getValue())).put("subs", String.join(", ", l)));
            }
            s.put("on", on).put("adv", advertising).put("error", error).put("name", bm != null ? phoneName() : "").put("clients", cl).put("sent", sent)
                    .put("live", live()).put("sp", curSp).put("inc", curInc).put("cad", curCad).put("hr", curHr)
                    .put("tm", tmSp == null ? JSONObject.NULL : String.format(Locale.FRANCE, "%.1f km/h · %.1f %%", tmSp, tmInc == null ? 0 : tmInc));
        } catch (Exception ignored) { }
        return s;
    }
}
