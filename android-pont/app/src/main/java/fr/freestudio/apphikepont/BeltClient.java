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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Real heart-rate belt connected by App Hike Connexion itself: chosen once, then connected and reconnected
 * automatically; each value goes to the watch server (heart-rate sensor clone).
 * A belt already connected to the phone (by App Hike for example) shares the same link.
 */
@SuppressWarnings("MissingPermission")
public class BeltClient {
    interface Host { void log(String s); void changed(); void heartRate(int bpm); boolean btAllowed(); }

    static final UUID HRS = WatchServer.u16(0x180D), HRM = WatchServer.u16(0x2A37), CCCD = WatchServer.u16(0x2902);
    final Context ctx;
    final Host host;
    final Handler h = new Handler(Looper.getMainLooper());
    final SharedPreferences p;
    final BluetoothManager bm;
    BluetoothGatt g;
    boolean connected, scanning;
    int bpm;
    long bpmAt, values;
    String status = "";
    final List<String[]> found = new ArrayList<>();

    BeltClient(Context c, Host hs) {
        ctx = c; host = hs;
        p = c.getSharedPreferences("belt", Context.MODE_PRIVATE);
        bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
    }

    String addr() { return p.getString("addr", null); }
    String name() { return p.getString("name", null); }
    boolean wanted() { return p.getBoolean("on", false) && addr() != null; }

    // ------------------------------------------------------------ choice of the belt
    final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) { h.post(() -> add(r.getDevice(), r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null, r.getScanRecord() != null ? r.getScanRecord().getServiceUuids() : null)); }
    };

    void add(BluetoothDevice d, String n, List<ParcelUuid> uu) {
        if (n == null) try { n = d.getName(); } catch (Exception ignored) { }
        boolean hr = (uu != null && uu.contains(new ParcelUuid(HRS))) || (n != null && n.matches("(?i).*(hr|heart|cardio|sensor|polar|wahoo|garmin|suunto|coospo|magene|h10|h9|tickr|hrm).*"));
        if (!hr) return;
        for (String[] f : found) if (f[0].equals(d.getAddress())) { if (f[1] == null && n != null) { f[1] = n; host.changed(); } return; }
        found.add(new String[]{d.getAddress(), n});
        host.changed();
    }

    void scan(boolean on) {
        BluetoothAdapter a = bm.getAdapter();
        if (on) {
            if (a == null || !a.isEnabled() || !host.btAllowed()) { status = "Bluetooth indisponible"; host.changed(); return; }
            found.clear();
            // belts already connected to the phone do not advertise: they are listed too
            try { for (BluetoothDevice d : bm.getConnectedDevices(BluetoothProfile.GATT)) add(d, null, null); } catch (Exception ignored) { }
            BluetoothLeScanner sc = a.getBluetoothLeScanner();
            if (sc == null) return;
            try { sc.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb); scanning = true; } catch (Exception e) { host.log("ceinture : recherche impossible (" + e.getMessage() + ")"); }
            h.postDelayed(() -> scan(false), 30000);
        } else if (scanning) {
            scanning = false;
            try { a.getBluetoothLeScanner().stopScan(scanCb); } catch (Exception ignored) { }
        }
        host.changed();
    }

    void pick(String addr, String name) {
        scan(false);
        stop();
        p.edit().putString("addr", addr).putString("name", name).putBoolean("on", true).apply();
        host.log("ceinture choisie : " + (name != null ? name : addr));
        start();
    }

    void setOn(boolean on) {
        p.edit().putBoolean("on", on).apply();
        if (on) start(); else stop();
    }

    // ------------------------------------------------------------ connection
    final Runnable retry = this::start;
    final Runnable watchdog = () -> { if (!connected) { host.log("ceinture : pas de réponse, nouvel essai"); close(); later(); } };

    void later() { h.removeCallbacks(retry); if (wanted()) h.postDelayed(retry, 5000); }

    void start() {
        h.removeCallbacks(retry);
        if (!wanted() || g != null) return;
        BluetoothAdapter a = bm.getAdapter();
        if (a == null || !a.isEnabled() || !host.btAllowed()) { status = "Bluetooth indisponible"; later(); host.changed(); return; }
        BluetoothDevice d;
        try { d = a.getRemoteDevice(addr()); } catch (Exception e) { status = "adresse invalide"; host.changed(); return; }
        status = "connexion…";
        g = d.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
        h.removeCallbacks(watchdog);
        h.postDelayed(watchdog, 15000);
        host.changed();
    }

    void stop() {
        h.removeCallbacks(retry);
        h.removeCallbacks(watchdog);
        boolean was = g != null;
        close();
        status = "";
        if (was) host.log("ceinture : déconnectée (relais arrêté)");
        host.changed();
    }

    void close() {
        connected = false;
        BluetoothGatt old = g;
        g = null;
        if (old != null) { try { old.disconnect(); } catch (Exception ignored) { } try { old.close(); } catch (Exception ignored) { } }
    }

    final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt gatt, int st, int ns) {
            h.post(() -> {
                if (gatt != g) return;
                if (ns == BluetoothProfile.STATE_CONNECTED) { status = "découverte…"; gatt.discoverServices(); }
                else if (ns == BluetoothProfile.STATE_DISCONNECTED) {
                    boolean was = connected;
                    close();
                    status = "perdue, nouvelle recherche…";
                    host.log("ceinture : " + (was ? "liaison perdue" : "connexion impossible (statut " + st + ")") + ", nouvel essai dans 5 s");
                    later();
                    host.changed();
                }
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt gatt, int st) {
            h.post(() -> {
                if (gatt != g) return;
                BluetoothGattService s = gatt.getService(HRS);
                BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(HRM);
                if (c == null) { host.log("ceinture : service cardio introuvable"); close(); later(); return; }
                gatt.setCharacteristicNotification(c, true);
                BluetoothGattDescriptor d = c.getDescriptor(CCCD);
                if (d == null) return;
                if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                else { d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); gatt.writeDescriptor(d); }
            });
        }

        @Override public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor d, int st) {
            h.post(() -> {
                if (gatt != g) return;
                h.removeCallbacks(watchdog);
                if (st != 0) { host.log("ceinture : abonnement refusé (statut " + st + ")"); close(); later(); return; }
                connected = true; status = "connectée";
                host.log("ceinture : " + (name() != null ? name() : addr()) + " connectée, fréquence relayée");
                host.changed();
            });
        }

        void value(BluetoothGatt gatt, byte[] v) {
            if (v == null || v.length < 2) return;
            int b = (v[0] & 1) != 0 && v.length >= 3 ? ((v[1] & 0xff) | ((v[2] & 0xff) << 8)) : (v[1] & 0xff);
            h.post(() -> {
                if (gatt != g) return;
                bpm = b; bpmAt = SystemClock.elapsedRealtime(); values++;
                host.heartRate(b);
                if (values == 1) host.log("ceinture : première valeur " + b + " bpm");
                host.changed();
            });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic c, byte[] v) { value(gatt, v); }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic c) { if (Build.VERSION.SDK_INT < 33) value(gatt, c.getValue()); }
    };

    JSONObject state() {
        JSONObject s = new JSONObject();
        try {
            JSONArray f = new JSONArray();
            for (String[] x : found) f.put(new JSONObject().put("id", x[0]).put("name", x[1] == null ? JSONObject.NULL : x[1]));
            s.put("on", p.getBoolean("on", false)).put("addr", addr() == null ? JSONObject.NULL : addr()).put("name", name() == null ? JSONObject.NULL : name())
                    .put("connected", connected).put("status", status).put("scanning", scanning).put("found", f)
                    .put("bpm", connected && SystemClock.elapsedRealtime() - bpmAt < 5000 ? bpm : 0);
        } catch (Exception ignored) { }
        return s;
    }
}
