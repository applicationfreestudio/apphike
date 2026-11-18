package fr.freestudio.apphikectrl;

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
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bluetooth server that clones the treadmill for other devices (a watch, an app): it advertises a Fitness Machine
 * (FTMS treadmill) and re-sends, byte for byte, the data and status messages received from the real treadmill.
 * Features and ranges are those read from the real treadmill (defaults until then).
 * Its control point only grants control and answers "not supported" to commands: the treadmill stays driven by this app.
 */
@SuppressWarnings("MissingPermission")
public class FtmsServer {
    static final UUID FTMS = MainActivity.uuid16(0x1826), FEATURE = MainActivity.uuid16(0x2ACC), DATA = MainActivity.uuid16(0x2ACD),
            TRAINING = MainActivity.uuid16(0x2AD3), SPEED_RANGE = MainActivity.uuid16(0x2AD4), INCL_RANGE = MainActivity.uuid16(0x2AD5),
            CP = MainActivity.uuid16(0x2AD9), STATUS = MainActivity.uuid16(0x2ADA), CCCD = MainActivity.uuid16(0x2902);

    interface Listener { void log(String s); void changed(); }

    final Context ctx;
    final Listener L;
    final Handler h = new Handler(Looper.getMainLooper());
    BluetoothManager bm;
    BluetoothGattServer server;
    BluetoothLeAdvertiser adv;
    boolean on, advertising;
    String error = "";
    final Map<String, BluetoothDevice> clients = new HashMap<>();
    final Map<String, Set<UUID>> subscribed = new HashMap<>();

    // values cloned from the real treadmill (defaults: distance, inclination, elapsed time; speed and inclination targets)
    byte[] feature = {0x0C, 0x10, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00};
    byte[] speedRange = {(byte) 100, 0, (byte) 0x40, 0x06, 10, 0};       // 1.00 – 16.00 km/h, step 0.1
    byte[] inclRange = {0, 0, (byte) 240, 0, 10, 0};                       // 0 – 24.0 %, step 1.0
    byte[] training = {0x00, 0x01};

    final AdvertiseCallback advCb = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings s) { h.post(() -> { advertising = true; error = ""; L.log("clone : émission en cours sous le nom « " + name() + " »"); L.changed(); }); }
        @Override public void onStartFailure(int code) { h.post(() -> { advertising = false; error = "émission refusée par Android (code " + code + ")"; L.log("✗ clone : " + error); L.changed(); }); }
    };

    FtmsServer(Context c, Listener l) { ctx = c; L = l; }

    String name() { try { BluetoothAdapter a = bm.getAdapter(); return a.getName() != null ? a.getName() : "téléphone"; } catch (Exception e) { return "téléphone"; } }

    int clientCount() { return clients.size(); }

    String clientNames() {
        StringBuilder b = new StringBuilder();
        for (BluetoothDevice d : clients.values()) { if (b.length() > 0) b.append(", "); String n = null; try { n = d.getName(); } catch (Exception ignored) { } b.append(n != null ? n : d.getAddress()); }
        return b.toString();
    }

    void start() {
        if (on) return;
        bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter a = bm.getAdapter();
        if (a == null || !a.isEnabled()) { error = "Bluetooth désactivé"; L.changed(); return; }
        if (!a.isMultipleAdvertisementSupported() || a.getBluetoothLeAdvertiser() == null) { error = "ce téléphone ne sait pas émettre en Bluetooth"; L.log("✗ clone : " + error); L.changed(); return; }
        on = true; error = "";
        server = bm.openGattServer(ctx, cb);
        if (server == null) { on = false; error = "serveur Bluetooth impossible à ouvrir"; L.log("✗ clone : " + error); L.changed(); return; }
        BluetoothGattService s = new BluetoothGattService(FTMS, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        s.addCharacteristic(chr(FEATURE, BluetoothGattCharacteristic.PROPERTY_READ, false));
        s.addCharacteristic(chr(DATA, BluetoothGattCharacteristic.PROPERTY_NOTIFY, true));
        s.addCharacteristic(chr(TRAINING, BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY, true));
        s.addCharacteristic(chr(SPEED_RANGE, BluetoothGattCharacteristic.PROPERTY_READ, false));
        s.addCharacteristic(chr(INCL_RANGE, BluetoothGattCharacteristic.PROPERTY_READ, false));
        s.addCharacteristic(chr(CP, BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_INDICATE, true));
        s.addCharacteristic(chr(STATUS, BluetoothGattCharacteristic.PROPERTY_NOTIFY, true));
        server.addService(s);   // advertising starts in onServiceAdded
        L.changed();
    }

    BluetoothGattCharacteristic chr(UUID u, int props, boolean cccd) {
        int perms = (props & BluetoothGattCharacteristic.PROPERTY_READ) != 0 ? BluetoothGattCharacteristic.PERMISSION_READ : 0;
        if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) perms |= BluetoothGattCharacteristic.PERMISSION_WRITE;
        BluetoothGattCharacteristic c = new BluetoothGattCharacteristic(u, props, perms);
        if (cccd) c.addDescriptor(new BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        return c;
    }

    void advertise() {
        adv = bm.getAdapter().getBluetoothLeAdvertiser();
        if (adv == null) { error = "émetteur Bluetooth indisponible"; L.changed(); return; }
        AdvertiseSettings set = new AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).setConnectable(true).setTimeout(0).build();
        // FTMS service data: "fitness machine available", type = treadmill
        AdvertiseData data = new AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(new ParcelUuid(FTMS))
                .addServiceData(new ParcelUuid(FTMS), new byte[]{0x01, 0x01, 0x00}).build();
        AdvertiseData resp = new AdvertiseData.Builder().setIncludeDeviceName(true).build();
        adv.startAdvertising(set, data, resp, advCb);
    }

    void stop() {
        if (!on) return;
        on = false; advertising = false;
        try { if (adv != null) adv.stopAdvertising(advCb); } catch (Exception ignored) { }
        if (server != null) {
            for (BluetoothDevice d : clients.values()) { try { server.cancelConnection(d); } catch (Exception ignored) { } }
            try { server.close(); } catch (Exception ignored) { }
        }
        server = null; clients.clear(); subscribed.clear();
        L.log("clone : arrêté");
        L.changed();
    }

    // ---- values coming from the real treadmill ----
    void setFeature(byte[] v) { if (v != null && v.length >= 8) feature = v.clone(); }
    void setSpeedRange(byte[] v) { if (v != null && v.length >= 6) speedRange = v.clone(); }
    void setInclRange(byte[] v) { if (v != null && v.length >= 6) inclRange = v.clone(); }
    void data(byte[] v) { notifyAll(DATA, v, false); }
    void status(byte[] v) { notifyAll(STATUS, v, false); }
    void belt(boolean running) {
        byte[] t = {0x00, (byte) (running ? 0x0D : 0x01)};   // training status: manual mode / idle
        if (!Arrays.equals(t, training)) { training = t; notifyAll(TRAINING, t, false); }
    }

    void notifyAll(UUID u, byte[] v, boolean indicate) {
        if (!on || server == null || v == null) return;
        BluetoothGattService s = server.getService(FTMS);
        if (s == null) return;
        BluetoothGattCharacteristic c = s.getCharacteristic(u);
        for (Map.Entry<String, BluetoothDevice> e : clients.entrySet()) {
            Set<UUID> sub = subscribed.get(e.getKey());
            if (sub == null || !sub.contains(u)) continue;
            send(e.getValue(), c, v, indicate);
        }
    }

    void send(BluetoothDevice d, BluetoothGattCharacteristic c, byte[] v, boolean indicate) {
        if (server == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 33) server.notifyCharacteristicChanged(d, c, indicate, v);
            else { c.setValue(v); server.notifyCharacteristicChanged(d, c, indicate); }
        } catch (Exception ignored) { }
    }

    byte[] readValue(UUID u) {
        if (u.equals(FEATURE)) return feature;
        if (u.equals(SPEED_RANGE)) return speedRange;
        if (u.equals(INCL_RANGE)) return inclRange;
        if (u.equals(TRAINING)) return training;
        return new byte[0];
    }

    final BluetoothGattServerCallback cb = new BluetoothGattServerCallback() {
        @Override public void onServiceAdded(int st, BluetoothGattService s) { h.post(() -> { if (!on) return; if (st == 0) advertise(); else { error = "service FTMS refusé (statut " + st + ")"; L.log("✗ clone : " + error); L.changed(); } }); }

        @Override public void onConnectionStateChange(BluetoothDevice d, int st, int ns) {
            h.post(() -> {
                if (!on) return;
                String n = null; try { n = d.getName(); } catch (Exception ignored) { }
                if (ns == BluetoothProfile.STATE_CONNECTED) { clients.put(d.getAddress(), d); L.log("clone : " + (n != null ? n : d.getAddress()) + " connecté"); }
                else if (ns == BluetoothProfile.STATE_DISCONNECTED) { if (clients.remove(d.getAddress()) != null) L.log("clone : " + (n != null ? n : d.getAddress()) + " déconnecté"); subscribed.remove(d.getAddress()); }
                L.changed();
            });
        }

        @Override public void onCharacteristicReadRequest(BluetoothDevice d, int id, int off, BluetoothGattCharacteristic c) {
            byte[] v = readValue(c.getUuid());
            byte[] part = off >= v.length ? new byte[0] : Arrays.copyOfRange(v, off, v.length);
            try { server.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, off, part); } catch (Exception ignored) { }
        }

        @Override public void onDescriptorReadRequest(BluetoothDevice d, int id, int off, BluetoothGattDescriptor de) {
            Set<UUID> sub = subscribed.get(d.getAddress());
            boolean en = sub != null && sub.contains(de.getCharacteristic().getUuid());
            byte[] v = en ? (de.getCharacteristic().getUuid().equals(CP) ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
            try { server.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, 0, v); } catch (Exception ignored) { }
        }

        @Override public void onDescriptorWriteRequest(BluetoothDevice d, int id, BluetoothGattDescriptor de, boolean prep, boolean resp, int off, byte[] v) {
            UUID u = de.getCharacteristic().getUuid();
            boolean en = v != null && v.length >= 1 && (v[0] & 0x03) != 0;
            h.post(() -> {
                Set<UUID> sub = subscribed.get(d.getAddress());
                if (sub == null) { sub = new HashSet<>(); subscribed.put(d.getAddress(), sub); }
                if (en) sub.add(u); else sub.remove(u);
                if (!clients.containsKey(d.getAddress())) clients.put(d.getAddress(), d);
                L.changed();
            });
            if (resp) try { server.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, 0, v); } catch (Exception ignored) { }
        }

        @Override public void onCharacteristicWriteRequest(BluetoothDevice d, int id, BluetoothGattCharacteristic c, boolean prep, boolean resp, int off, byte[] v) {
            if (resp) try { server.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, 0, null); } catch (Exception ignored) { }
            if (!c.getUuid().equals(CP) || v == null || v.length < 1) return;
            int op = v[0] & 0xff;
            // request control (00) and reset (01) are accepted; commands from outside are not passed to the treadmill
            byte code = (byte) (op == 0x00 || op == 0x01 ? 0x01 : 0x02);
            h.post(() -> {
                String n = null; try { n = d.getName(); } catch (Exception ignored) { }
                L.log("clone : commande 0x" + String.format("%02x", op) + " de " + (n != null ? n : d.getAddress()) + (code == 1 ? " acceptée" : " ignorée (le pilotage reste dans l'app)"));
                h.postDelayed(() -> send(d, c, new byte[]{(byte) 0x80, (byte) op, code}, true), 30);
            });
        }
    };
}
