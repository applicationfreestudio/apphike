package fr.freestudio.apphikepont;

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
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Native Bluetooth of the bridge, driven by the App Hike page through the local channel (Web Bluetooth polyfill of index.html).
 * Device choice: the bridge scans and sends the matching devices to the page ("found"), which shows the list.
 * Devices are identified by their Bluetooth address. One GATT operation at a time per device (Android requires it),
 * each one answered to the page with its request number. Notifications go to the page as "val" events.
 */
@SuppressWarnings("MissingPermission")
class Ble {
    interface Out { void reply(int rq, Object value, String errName, String errMsg); void event(JSONObject e); }
    interface Host { boolean btAllowed(); void log(String s); void changed(); }

    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    final Context act;
    final Host host;
    final Out out;
    final Handler h = new Handler(Looper.getMainLooper());
    final SharedPreferences prefs;
    BluetoothAdapter adapter;

    Ble(Context a, Host hs, Out o) {
        act = a; host = hs; out = o;
        prefs = a.getSharedPreferences("ble", Context.MODE_PRIVATE);
        adapter = ((BluetoothManager) a.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
    }

    static String hex(byte[] v) {
        if (v == null) return "";
        StringBuilder b = new StringBuilder();
        for (byte x : v) b.append(String.format("%02x", x & 0xff));
        return b.toString();
    }

    static byte[] unhex(String s) {
        if (s == null) return new byte[0];
        byte[] v = new byte[s.length() / 2];
        for (int i = 0; i < v.length; i++) v[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return v;
    }

    boolean ready() { return adapter != null && adapter.isEnabled() && host.btAllowed(); }

    // ------------------------------------------------------------ allowed devices (navigator.bluetooth.getDevices)
    JSONArray allowed() {
        try { return new JSONArray(prefs.getString("allowed", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    void allow(String id, String name) {
        try {
            JSONArray a = allowed(), n = new JSONArray();
            for (int i = 0; i < a.length(); i++) if (!a.getJSONObject(i).optString("id").equals(id)) n.put(a.getJSONObject(i));
            n.put(new JSONObject().put("id", id).put("name", name == null ? JSONObject.NULL : name));
            prefs.edit().putString("allowed", n.toString()).apply();
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ scanning (chooser + watchAdvertisements)
    final Set<String> watched = new HashSet<>();
    final Map<String, Long> lastAdv = new HashMap<>();
    final Map<String, String> names = new HashMap<>();
    boolean scanning;
    final Runnable stopLater = () -> { if (watched.isEmpty() && Ble.this.filters == null) stopScan(); };

    final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) { h.post(() -> onAdv(r)); }
        @Override public void onBatchScanResults(List<ScanResult> rs) { h.post(() -> { for (ScanResult r : rs) onAdv(r); }); }
        @Override public void onScanFailed(int code) { h.post(() -> { scanning = false; host.log("scan refusé par Android (code " + code + ")"); }); }
    };

    void startScan() {
        h.removeCallbacks(stopLater);
        if (scanning || !ready()) return;
        BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
        if (sc == null) return;
        try { sc.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb); scanning = true; } catch (Exception e) { host.log("scan impossible : " + e.getMessage()); }
    }

    void stopScan() {
        if (!scanning) return;
        scanning = false;
        try { adapter.getBluetoothLeScanner().stopScan(scanCb); } catch (Exception ignored) { }
    }

    /** Stops the scan a little later, so that Android does not refuse frequent restarts (5 per 30 s). */
    void maybeStop() { h.removeCallbacks(stopLater); h.postDelayed(stopLater, 4000); }

    void onAdv(ScanResult r) {
        BluetoothDevice d = r.getDevice();
        String id = d.getAddress();
        String n = r.getScanRecord() != null && r.getScanRecord().getDeviceName() != null ? r.getScanRecord().getDeviceName() : d.getName();
        if (n != null) names.put(id, n);
        if (watched.contains(id)) {
            long t = SystemClock.elapsedRealtime();
            Long l = lastAdv.get(id);
            if (l == null || t - l > 1000) {
                lastAdv.put(id, t);
                try { out.event(new JSONObject().put("t", "adv").put("id", id).put("rssi", r.getRssi())); } catch (Exception ignored) { }
            }
        }
        if (filters != null) chooserAdd(r, id, n);
    }

    void watch(String id, boolean on) {
        if (on) {
            watched.add(id); lastAdv.remove(id); startScan();
            // a device already connected here no longer advertises: the page is told at once that it is reachable
            Conn c = conns.get(id);
            if (c != null && c.connected) h.postDelayed(() -> {
                if (watched.contains(id)) try { out.event(new JSONObject().put("t", "adv").put("id", id).put("rssi", 0)); } catch (Exception ignored) { }
            }, 100);
        }
        else { watched.remove(id); maybeStop(); }
    }

    // ------------------------------------------------------------ device search for the page's chooser
    final List<String[]> found = new ArrayList<>();
    JSONArray filters;

    /** Starts (on=true) or stops the search; each matching device is sent to the page as a "found" event. */
    void scan(String filtersJson, boolean on) {
        if (!on) { filters = null; maybeStop(); return; }
        if (!ready()) {
            try { out.event(new JSONObject().put("t", "scanerr").put("e", adapter == null ? "Bluetooth absent" : !adapter.isEnabled() ? "Active le Bluetooth du téléphone." : "Autorise « Appareils à proximité » dans l'app App Hike Connexion.")); } catch (Exception ignored) { }
            return;
        }
        try { filters = new JSONObject(filtersJson).optJSONArray("filters"); } catch (Exception e) { filters = null; }
        if (filters == null) filters = new JSONArray();
        found.clear();
        startScan();
    }

    boolean matches(ScanResult r, String name) {
        if (filters == null || filters.length() == 0) return true;
        List<ParcelUuid> su = r.getScanRecord() != null ? r.getScanRecord().getServiceUuids() : null;
        for (int i = 0; i < filters.length(); i++) {
            JSONObject f = filters.optJSONObject(i);
            if (f == null) continue;
            boolean ok = true;
            JSONArray s = f.optJSONArray("services");
            if (s != null) for (int k = 0; k < s.length() && ok; k++) {
                ParcelUuid u = ParcelUuid.fromString(s.optString(k));
                ok = su != null && su.contains(u);
            }
            if (ok && f.has("namePrefix")) ok = name != null && name.startsWith(f.optString("namePrefix"));
            if (ok && f.has("name")) ok = name != null && name.equals(f.optString("name"));
            if (ok) return true;
        }
        return false;
    }

    void chooserAdd(ScanResult r, String id, String name) {
        for (String[] f : found) if (f[0].equals(id)) {
            if (f[1] == null && name != null) { f[1] = name; sendFound(f); }
            return;
        }
        if (!matches(r, name)) return;
        String[] f = {id, name};
        found.add(f);
        sendFound(f);
    }

    void sendFound(String[] f) {
        try { out.event(new JSONObject().put("t", "found").put("id", f[0]).put("name", f[1] == null ? JSONObject.NULL : f[1])); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ connections
    static class Op { int rq; String kind; UUID s, c; byte[] v; boolean flag; Op(int rq, String k) { this.rq = rq; kind = k; } }

    class Conn {
        final String id;
        BluetoothGatt g;
        boolean connected;
        int connRq = -1;
        final ArrayDeque<Op> q = new ArrayDeque<>();
        Op cur;
        final Runnable opTimeout = () -> { if (cur != null) { Op o = cur; cur = null; out.reply(o.rq, null, "NetworkError", "Pas de réponse de l'appareil (" + o.kind + ")"); pump(); } };
        Conn(String id) { this.id = id; }

        void pump() {
            if (cur != null || q.isEmpty() || g == null) return;
            Op o = q.poll();
            cur = o;
            BluetoothGattService sv = g.getService(o.s);
            BluetoothGattCharacteristic c = sv == null ? null : sv.getCharacteristic(o.c);
            if (c == null) { cur = null; out.reply(o.rq, null, "NotFoundError", "Caractéristique introuvable"); pump(); return; }
            boolean ok;
            switch (o.kind) {
                case "notify": {
                    g.setCharacteristicNotification(c, o.flag);
                    BluetoothGattDescriptor d = c.getDescriptor(CCCD);
                    if (d == null) { cur = null; out.reply(o.rq, true, null, null); pump(); return; }
                    int p = c.getProperties();
                    byte[] val = !o.flag ? BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                            : (p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                    if (Build.VERSION.SDK_INT >= 33) ok = g.writeDescriptor(d, val) == 0;
                    else { d.setValue(val); ok = g.writeDescriptor(d); }
                    break;
                }
                case "write": {
                    int wt = o.flag ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
                    if (Build.VERSION.SDK_INT >= 33) ok = g.writeCharacteristic(c, o.v, wt) == 0;
                    else { c.setValue(o.v); c.setWriteType(wt); ok = g.writeCharacteristic(c); }
                    break;
                }
                default: ok = g.readCharacteristic(c);
            }
            if (!ok) { cur = null; out.reply(o.rq, null, "NetworkError", "Opération refusée par Android (" + o.kind + ")"); h.postDelayed(this::pump, 50); return; }
            h.postDelayed(opTimeout, 8000);
        }

        void done(String kind, int st, Object value) {
            if (cur == null || !cur.kind.equals(kind)) return;
            h.removeCallbacks(opTimeout);
            Op o = cur; cur = null;
            if (st == 0) out.reply(o.rq, value, null, null); else out.reply(o.rq, null, "NetworkError", "Erreur GATT " + st);
            pump();
        }

        void failAll(String why) {
            h.removeCallbacks(opTimeout);
            if (cur != null) { out.reply(cur.rq, null, "NetworkError", why); cur = null; }
            for (Op o : q) out.reply(o.rq, null, "NetworkError", why);
            q.clear();
        }

        final BluetoothGattCallback cb = new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt gatt, int st, int ns) {
                h.post(() -> {
                    if (gatt != g) return;
                    if (ns == BluetoothProfile.STATE_CONNECTED) { if (!gatt.discoverServices()) lost(st, "découverte des services refusée"); }
                    else if (ns == BluetoothProfile.STATE_DISCONNECTED) lost(st, null);
                });
            }

            @Override public void onServicesDiscovered(BluetoothGatt gatt, int st) {
                h.post(() -> {
                    if (gatt != g || connRq < 0) return;
                    if (st != 0) { lost(st, "découverte des services en échec"); return; }
                    connected = true;
                    int rq = connRq; connRq = -1;
                    host.log("connecté : " + nameOf(id));
                    host.changed();
                    try {
                        JSONArray a = new JSONArray();
                        for (BluetoothGattService s : gatt.getServices()) {
                            JSONArray cs = new JSONArray();
                            for (BluetoothGattCharacteristic c : s.getCharacteristics()) cs.put(new JSONObject().put("u", c.getUuid().toString()).put("p", c.getProperties()));
                            a.put(new JSONObject().put("u", s.getUuid().toString()).put("c", cs));
                        }
                        out.reply(rq, a, null, null);
                    } catch (Exception e) { out.reply(rq, null, "NetworkError", e.getMessage()); }
                });
            }

            @Override public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor d, int st) { h.post(() -> { if (gatt == g) done("notify", st, true); }); }

            @Override public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic c, int st) { h.post(() -> { if (gatt == g) done("write", st, true); }); }

            @Override public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic c, byte[] v, int st) {
                String hx = hex(v);
                h.post(() -> { if (gatt == g) done("read", st, hx); });
            }

            @SuppressWarnings("deprecation")
            @Override public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic c, int st) {
                if (Build.VERSION.SDK_INT >= 33) return;
                String hx = hex(c.getValue());
                h.post(() -> { if (gatt == g) done("read", st, hx); });
            }

            void changed(BluetoothGatt gatt, BluetoothGattCharacteristic c, byte[] v) {
                String hx = hex(v), s = c.getService().getUuid().toString(), u = c.getUuid().toString();
                h.post(() -> {
                    if (gatt != g) return;
                    try { out.event(new JSONObject().put("t", "val").put("id", id).put("s", s).put("c", u).put("h", hx)); } catch (Exception ignored) { }
                });
            }

            @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic c, byte[] v) { changed(gatt, c, v); }

            @SuppressWarnings("deprecation")
            @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic c) {
                if (Build.VERSION.SDK_INT >= 33) return;
                changed(gatt, c, c.getValue());
            }
        };

        /** Link lost or connection failed: the page gets a rejected connect() or a gattserverdisconnected event. */
        void lost(int st, String why) {
            boolean was = connected;
            int rq = connRq;
            connected = false; connRq = -1;
            failAll("GATT Server is disconnected.");
            BluetoothGatt old = g; g = null;
            if (old != null) { try { old.disconnect(); } catch (Exception ignored) { } try { old.close(); } catch (Exception ignored) { } }
            conns.remove(id);
            if (rq >= 0) out.reply(rq, null, "NetworkError", why != null ? why : "Connexion impossible (statut " + st + ")");
            if (was) { host.log("déconnecté : " + nameOf(id)); try { out.event(new JSONObject().put("t", "disc").put("id", id)); } catch (Exception ignored) { } }
            host.changed();
        }
    }

    final Map<String, Conn> conns = new HashMap<>();

    void connect(int rq, String id) {
        if (!ready()) { out.reply(rq, null, "NetworkError", "Bluetooth indisponible ou non autorisé"); return; }
        Conn c = conns.get(id);
        if (c != null && c.connected) { out.reply(rq, servicesOf(c), null, null); return; }
        if (c != null && c.connRq >= 0) { out.reply(c.connRq, null, "AbortError", "Connexion remplacée"); c.connRq = rq; return; }
        BluetoothDevice d;
        try { d = adapter.getRemoteDevice(id); } catch (Exception e) { out.reply(rq, null, "NotFoundError", "Appareil inconnu"); return; }
        c = new Conn(id);
        c.connRq = rq;
        conns.put(id, c);
        c.g = d.connectGatt(act, false, c.cb, BluetoothDevice.TRANSPORT_LE);
        if (c.g == null) { conns.remove(id); out.reply(rq, null, "NetworkError", "Connexion refusée par Android"); }
    }

    JSONArray servicesOf(Conn c) {
        JSONArray a = new JSONArray();
        try {
            for (BluetoothGattService s : c.g.getServices()) {
                JSONArray cs = new JSONArray();
                for (BluetoothGattCharacteristic ch : s.getCharacteristics()) cs.put(new JSONObject().put("u", ch.getUuid().toString()).put("p", ch.getProperties()));
                a.put(new JSONObject().put("u", s.getUuid().toString()).put("c", cs));
            }
        } catch (Exception ignored) { }
        return a;
    }

    void disconnect(String id) {
        Conn c = conns.get(id);
        if (c == null) return;
        if (c.connRq >= 0) { out.reply(c.connRq, null, "AbortError", "Connection attempt aborted."); c.connRq = -1; }
        c.lost(0, null);
    }

    void op(int rq, String id, String kind, String s, String ch, String hx, boolean flag) {
        Conn c = conns.get(id);
        if (c == null || !c.connected) { out.reply(rq, null, "NetworkError", "GATT Server is disconnected. Cannot perform GATT operations."); return; }
        Op o = new Op(rq, kind);
        try { o.s = UUID.fromString(s); o.c = UUID.fromString(ch); } catch (Exception e) { out.reply(rq, null, "NotFoundError", "UUID invalide"); return; }
        o.v = unhex(hx);
        o.flag = flag;
        c.q.add(o);
        c.pump();
    }

    void closeAll() {
        stopScan();
        for (Conn c : new ArrayList<>(conns.values())) { c.connRq = -1; c.lost(0, null); }
        conns.clear();
    }

    String nameOf(String id) {
        String n = names.get(id);
        if (n == null) {
            JSONArray a = allowed();
            for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i).optString("id").equals(id)) n = a.optJSONObject(i).optString("name", null);
        }
        return n != null ? n : id;
    }

    /** Names of the connected devices, for the notification. */
    String connectedNames() {
        StringBuilder b = new StringBuilder();
        for (Conn c : conns.values()) if (c.connected) { if (b.length() > 0) b.append(", "); b.append(nameOf(c.id)); }
        return b.toString();
    }

    String describe() {
        return String.format(Locale.FRANCE, "%d connexion(s), scan %s", conns.size(), scanning ? "actif" : "arrêté");
    }
}
