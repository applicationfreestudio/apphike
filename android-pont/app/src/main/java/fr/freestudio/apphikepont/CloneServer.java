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
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bluetooth data server by cloning: every device chosen by the user (treadmill, belt…) is copied as it is — same services,
 * same characteristics, same advertised services — and the data it sends is passed on byte for byte to the watch.
 * Data only: what the watch writes is not passed on to the source device.
 * One advertisement per cloned device (Android advertising sets): the watch sees as many devices as clones. All clones share
 * the phone's GATT database and Bluetooth name (Android limits).
 * A source device is the one App Hike connects through the bridge; its structure is kept in a history, so a clone can be
 * prepared while the device is off and starts as soon as it is connected again.
 */
@SuppressWarnings("MissingPermission")
public class CloneServer {
    interface Host { void log(String s); void changed(); boolean isCentral(String address); }

    static UUID u16(int v) { return UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", v)); }
    static final UUID CCCD = u16(0x2902), GAP = u16(0x1800), GATTS = u16(0x1801), DIS = u16(0x180A), BAS = u16(0x180F);

    static String short16(UUID u) {
        String s = u.toString();
        return s.endsWith("-0000-1000-8000-00805f9b34fb") && s.startsWith("0000") ? "0x" + s.substring(4, 8).toUpperCase() : s;
    }

    final Context ctx;
    final Host host;
    final Ble ble;
    final Handler h = new Handler(Looper.getMainLooper());
    final SharedPreferences p;
    BluetoothManager bm;
    BluetoothGattServer server;
    BluetoothLeAdvertiser adv;
    boolean on, building;
    String error = "", oldName;
    long sent, sentFail;

    /** link between a characteristic of the server and the source device's characteristic */
    static class Ref { final String id; final UUID s, c; Ref(String id, UUID s, UUID c) { this.id = id; this.s = s; this.c = c; } String key() { return id + "|" + s + "|" + c; } }
    final Map<BluetoothGattCharacteristic, Ref> refs = new HashMap<>();
    final Map<String, byte[]> cache = new HashMap<>();
    final Map<String, BluetoothDevice> clients = new HashMap<>();
    final Map<String, Set<BluetoothGattCharacteristic>> subs = new HashMap<>();
    final ArrayDeque<BluetoothGattService> toAdd = new ArrayDeque<>();
    final Map<String, AdvertisingSetCallback> advs = new HashMap<>();
    final Map<String, String> advState = new HashMap<>();

    CloneServer(Context c, Host hs, Ble b) {
        ctx = c; host = hs; ble = b;
        p = c.getSharedPreferences("clone", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ history and clone list
    JSONArray hist() { try { return new JSONArray(p.getString("hist", "[]")); } catch (Exception e) { return new JSONArray(); } }
    JSONArray list() { try { return new JSONArray(p.getString("list", "[]")); } catch (Exception e) { return new JSONArray(); } }
    JSONObject histOf(String id) { JSONArray a = hist(); for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null && id.equals(o.optString("id"))) return o; } return null; }
    boolean cloned(String id) { JSONArray a = list(); for (int i = 0; i < a.length(); i++) if (id.equals(a.optString(i))) return true; return false; }

    /** A device connected through the bridge: its structure (and advertisement) goes to the history. */
    void ready(String id, BluetoothGatt g) {
        try {
            JSONArray sv = new JSONArray();
            for (BluetoothGattService s : g.getServices()) {
                JSONArray cs = new JSONArray();
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) cs.put(new JSONObject().put("u", c.getUuid().toString()).put("p", c.getProperties()));
                sv.put(new JSONObject().put("u", s.getUuid().toString()).put("c", cs));
            }
            JSONObject old = histOf(id), e = new JSONObject().put("id", id).put("name", ble.nameOf(id)).put("t", System.currentTimeMillis()).put("s", sv);
            JSONObject a = ble.advInfo.get(id);
            if (a != null) e.put("adv", a); else if (old != null && old.has("adv")) e.put("adv", old.get("adv"));
            JSONArray hs = hist(), n = new JSONArray();
            n.put(e);
            for (int i = 0; i < hs.length() && n.length() < 12; i++) { JSONObject o = hs.optJSONObject(i); if (o != null && !id.equals(o.optString("id"))) n.put(o); }
            p.edit().putString("hist", n.toString()).apply();
            boolean same = old != null && old.optJSONArray("s") != null && old.optJSONArray("s").toString().equals(sv.toString());
            if (on && cloned(id)) {
                if (!same) { host.log("clonage : structure de " + e.optString("name") + " mise à jour, serveur reconstruit"); rebuild(); }
                else feed(id);
            }
            host.changed();
        } catch (Exception x) { host.log("clonage : historique impossible (" + x.getMessage() + ")"); }
    }

    void gone(String id) { if (on && cloned(id)) { host.log("clonage : source " + ble.nameOf(id) + " déconnectée, plus de données"); host.changed(); } }

    void add(String id) {
        if (cloned(id)) return;
        JSONArray a = list(); a.put(id);
        p.edit().putString("list", a.toString()).apply();
        JSONObject e = histOf(id);
        host.log("clonage : ajout de " + (e != null ? e.optString("name") : id));
        if (on) rebuild(); else host.changed();
    }

    void del(String id) {
        JSONArray a = list(), n = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (!id.equals(a.optString(i))) n.put(a.optString(i));
        p.edit().putString("list", n.toString()).apply();
        Set<UUID> pn = ble.pinned.remove(id);
        JSONObject e = histOf(id);
        host.log("clonage : suppression de " + (e != null ? e.optString("name") : id));
        if (on) rebuild(); else host.changed();
    }

    void forget(String id) {
        if (cloned(id)) del(id);
        JSONArray a = hist(), n = new JSONArray();
        for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null && !id.equals(o.optString("id"))) n.put(o); }
        p.edit().putString("hist", n.toString()).apply();
        host.changed();
    }

    // ------------------------------------------------------------ start / stop
    void start() {
        if (on) return;
        on = true;
        host.log("clonage : diffusion activée");
        rebuild();
    }

    void stop() {
        if (!on) return;
        teardown();
        on = false; error = "";
        host.log("clonage : diffusion arrêtée");
        host.changed();
    }

    void teardown() {
        building = false;
        if (adv != null) for (AdvertisingSetCallback cb : advs.values()) { try { adv.stopAdvertisingSet(cb); } catch (Exception ignored) { } }
        advs.clear(); advState.clear();
        if (server != null) {
            for (BluetoothDevice d : clients.values()) { try { server.cancelConnection(d); } catch (Exception ignored) { } }
            try { server.close(); } catch (Exception ignored) { }
        }
        server = null; clients.clear(); subs.clear(); refs.clear(); toAdd.clear();
        ble.pinned.clear();
        restoreName();
    }

    void restoreName() {
        if (oldName == null || bm == null) return;
        try { bm.getAdapter().setName(oldName); host.log("clonage : nom Bluetooth du téléphone rétabli « " + oldName + " »"); } catch (Exception ignored) { }
        oldName = null;
    }

    /** (Re)builds the GATT database from the structure of every cloned device, then advertises one device per clone. */
    void rebuild() {
        teardown();
        error = "";
        bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter a = bm == null ? null : bm.getAdapter();
        if (a == null || !a.isEnabled()) { error = "Bluetooth désactivé"; host.changed(); return; }
        if (!a.isMultipleAdvertisementSupported() || a.getBluetoothLeAdvertiser() == null) { error = "ce téléphone ne sait pas émettre"; host.log("✗ clonage : " + error); host.changed(); return; }
        JSONArray l = list();
        List<JSONObject> srcs = new ArrayList<>();
        for (int i = 0; i < l.length(); i++) { JSONObject e = histOf(l.optString(i)); if (e != null) srcs.add(e); }
        if (srcs.isEmpty()) { error = l.length() == 0 ? "aucun appareil cloné" : "structure inconnue : connecte l'appareil une fois par App Hike"; host.changed(); return; }
        server = bm.openGattServer(ctx, cb);
        if (server == null) { error = "serveur Bluetooth impossible à ouvrir"; host.log("✗ clonage : " + error); host.changed(); return; }
        for (JSONObject e : srcs) {
            String id = e.optString("id");
            JSONArray sv = e.optJSONArray("s");
            if (sv == null) continue;
            for (int i = 0; i < sv.length(); i++) {
                JSONObject so = sv.optJSONObject(i);
                UUID su;
                try { su = UUID.fromString(so.optString("u")); } catch (Exception x) { continue; }
                if (su.equals(GAP) || su.equals(GATTS)) continue;   // owned by Android
                BluetoothGattService s = new BluetoothGattService(su, BluetoothGattService.SERVICE_TYPE_PRIMARY);
                JSONArray cs = so.optJSONArray("c");
                for (int k = 0; cs != null && k < cs.length(); k++) {
                    JSONObject co = cs.optJSONObject(k);
                    UUID cu;
                    try { cu = UUID.fromString(co.optString("u")); } catch (Exception x) { continue; }
                    int pr = co.optInt("p") & (BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE | BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE);
                    int perm = 0;
                    if ((pr & BluetoothGattCharacteristic.PROPERTY_READ) != 0) perm |= BluetoothGattCharacteristic.PERMISSION_READ;
                    if ((pr & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) perm |= BluetoothGattCharacteristic.PERMISSION_WRITE;
                    BluetoothGattCharacteristic c = new BluetoothGattCharacteristic(cu, pr, perm);
                    if ((pr & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0)
                        c.addDescriptor(new BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
                    s.addCharacteristic(c);
                    refs.put(c, new Ref(id, su, cu));
                }
                toAdd.add(s);
            }
        }
        if (toAdd.isEmpty()) { error = "aucun service à cloner"; host.changed(); return; }
        building = true;
        host.log("clonage : construction de " + toAdd.size() + " service(s) pour " + srcs.size() + " appareil(s)");
        server.addService(toAdd.poll());
        host.changed();
    }

    /** All services added: one advertisement per clone, then the sources feed the server. */
    void advertiseAll() {
        building = false;
        adv = bm.getAdapter().getBluetoothLeAdvertiser();
        if (adv == null) { error = "émetteur indisponible"; host.changed(); return; }
        JSONArray l = list();
        List<JSONObject> srcs = new ArrayList<>();
        for (int i = 0; i < l.length(); i++) { JSONObject e = histOf(l.optString(i)); if (e != null) srcs.add(e); }
        // a single clone takes the source's name (the phone's Bluetooth name, restored when the diffusion stops)
        if (srcs.size() == 1) {
            String nm = srcs.get(0).optString("name", "");
            if (!nm.isEmpty() && !nm.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}")) {
                BluetoothAdapter a = bm.getAdapter();
                if (!nm.equals(a.getName())) {
                    oldName = a.getName();
                    boolean ok = false;
                    try { ok = a.setName(nm); } catch (Exception ignored) { }
                    host.log("clonage : nom Bluetooth du téléphone « " + nm + " » " + (ok ? "(rétabli à l'arrêt)" : "refusé par Android"));
                    if (!ok) oldName = null;
                }
            }
        }
        for (JSONObject e : srcs) advertise(e);
        for (JSONObject e : srcs) feed(e.optString("id"));
        host.changed();
    }

    void advertise(JSONObject e) {
        String id = e.optString("id"), nm = e.optString("name");
        List<UUID> us = new ArrayList<>();
        Map<UUID, byte[]> sd = new HashMap<>();
        JSONObject ad = e.optJSONObject("adv");
        if (ad != null) {
            JSONArray a = ad.optJSONArray("u");
            for (int i = 0; a != null && i < a.length(); i++) try { us.add(UUID.fromString(a.optString(i))); } catch (Exception ignored) { }
            JSONObject d = ad.optJSONObject("sd");
            if (d != null) for (java.util.Iterator<String> it = d.keys(); it.hasNext(); ) { String k = it.next(); try { sd.put(UUID.fromString(k), Ble.unhex(d.optString(k))); } catch (Exception ignored) { } }
        }
        if (us.isEmpty()) {   // advertisement never seen: the device's main services
            JSONArray sv = e.optJSONArray("s");
            for (int i = 0; sv != null && i < sv.length(); i++) try {
                UUID u = UUID.fromString(sv.optJSONObject(i).optString("u"));
                if (!u.equals(GAP) && !u.equals(GATTS) && !u.equals(DIS) && !u.equals(BAS)) us.add(u);
            } catch (Exception ignored) { }
        }
        // legacy advertisement: 31 bytes (flags 3, then 16-bit UUIDs 2+2n, 128-bit 2+16n, service data 4+len)
        AdvertiseData.Builder d = new AdvertiseData.Builder().setIncludeDeviceName(false);
        int room = 31 - 3, n16 = 0, n128 = 0;
        List<String> shown = new ArrayList<>();
        for (UUID u : us) {
            boolean b16 = short16(u).startsWith("0x");
            int need = b16 ? (n16 == 0 ? 4 : 2) : (n128 == 0 ? 18 : 16);
            if (need > room) continue;
            room -= need; if (b16) n16++; else n128++;
            d.addServiceUuid(new ParcelUuid(u)); shown.add(short16(u));
        }
        for (Map.Entry<UUID, byte[]> x : sd.entrySet()) {
            int need = 4 + x.getValue().length;
            if (!short16(x.getKey()).startsWith("0x") || need > room) continue;
            room -= need;
            d.addServiceData(new ParcelUuid(x.getKey()), x.getValue());
        }
        AdvertiseData resp = new AdvertiseData.Builder().setIncludeDeviceName(true).build();
        AdvertisingSetParameters par = new AdvertisingSetParameters.Builder().setLegacyMode(true).setConnectable(true).setScannable(true)
                .setInterval(AdvertisingSetParameters.INTERVAL_LOW).setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH).build();
        AdvertisingSetCallback acb = new AdvertisingSetCallback() {
            @Override public void onAdvertisingSetStarted(AdvertisingSet set, int tx, int status) {
                h.post(() -> {
                    if (status == ADVERTISE_SUCCESS) { advState.put(id, "ok"); host.log("clonage : " + nm + " annoncé (" + String.join(", ", shown) + ")"); }
                    else { advState.put(id, "échec " + status); host.log("✗ clonage : annonce de " + nm + " refusée (code " + status + ")"); }
                    host.changed();
                });
            }
            @Override public void onAdvertisingSetStopped(AdvertisingSet set) { h.post(() -> { advState.remove(id); host.changed(); }); }
        };
        advs.put(id, acb);
        advState.put(id, "démarrage");
        try { adv.startAdvertisingSet(par, d.build(), resp, null, null, acb); }
        catch (Exception x) { advState.put(id, "échec"); host.log("✗ clonage : annonce de " + nm + " impossible (" + x.getMessage() + ")"); }
    }

    /** Source connected: its notifications are kept on (even if App Hike unsubscribes) and its readable values are read once. */
    void feed(String id) {
        if (!on || server == null || building || !ble.isConnected(id)) return;
        Set<UUID> pn = new HashSet<>();
        ble.pinned.put(id, pn);
        int nn = 0, nr = 0;
        for (Map.Entry<BluetoothGattCharacteristic, Ref> e : refs.entrySet()) {
            Ref r = e.getValue();
            if (!r.id.equals(id)) continue;
            int pr = e.getKey().getProperties();
            if ((pr & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) { pn.add(r.c); ble.internal(id, "notify", r.s, r.c, true, null); nn++; }
            if ((pr & BluetoothGattCharacteristic.PROPERTY_READ) != 0) { ble.internal(id, "read", r.s, r.c, false, v -> { if (v != null) cache.put(r.key(), v); }); nr++; }
        }
        host.log("clonage : " + ble.nameOf(id) + " relié (" + nn + " flux de données, " + nr + " valeurs lues)");
    }

    /** Data from a source device: kept for reads, and passed on as it is to the subscribed watches. */
    void value(String id, UUID s, UUID c, byte[] v) {
        if (!on) return;
        cache.put(id + "|" + s + "|" + c, v);
        if (server == null) return;
        for (Map.Entry<BluetoothGattCharacteristic, Ref> e : refs.entrySet()) {
            Ref r = e.getValue();
            if (!r.id.equals(id) || !r.c.equals(c) || !r.s.equals(s)) continue;
            BluetoothGattCharacteristic ch = e.getKey();
            boolean ind = (ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0;
            for (Map.Entry<String, BluetoothDevice> cl : clients.entrySet()) {
                Set<BluetoothGattCharacteristic> sb = subs.get(cl.getKey());
                if (sb == null || !sb.contains(ch)) continue;
                boolean ok;
                try {
                    if (Build.VERSION.SDK_INT >= 33) ok = server.notifyCharacteristicChanged(cl.getValue(), ch, ind, v) == 0;
                    else { ch.setValue(v); ok = server.notifyCharacteristicChanged(cl.getValue(), ch, ind); }
                } catch (Exception x) { ok = false; }
                if (ok) { sent++; if (sent == 1) host.log("clonage : premières données transmises à " + label(cl.getValue()) + " (" + short16(c) + ")"); }
                else if (++sentFail % 20 == 1) host.log("clonage : envoi refusé par Android (" + sentFail + " fois)");
            }
        }
    }

    String label(BluetoothDevice d) { String n = null; try { n = d.getName(); } catch (Exception ignored) { } return (n != null ? n : "appareil") + " " + d.getAddress(); }

    final BluetoothGattServerCallback cb = new BluetoothGattServerCallback() {
        @Override public void onServiceAdded(int st, BluetoothGattService s) {
            h.post(() -> {
                if (!on || server == null) return;
                if (st != 0) host.log("✗ clonage : service " + short16(s.getUuid()) + " refusé (statut " + st + ")");
                if (!toAdd.isEmpty()) server.addService(toAdd.poll());
                else advertiseAll();
            });
        }

        @Override public void onConnectionStateChange(BluetoothDevice d, int st, int ns) {
            h.post(() -> {
                if (!on || host.isCentral(d.getAddress())) return;
                if (ns == BluetoothProfile.STATE_CONNECTED) { clients.put(d.getAddress(), d); host.log("clonage : " + label(d) + " connecté au clone"); }
                else if (ns == BluetoothProfile.STATE_DISCONNECTED) { clients.remove(d.getAddress()); subs.remove(d.getAddress()); host.log("clonage : " + label(d) + " déconnecté"); }
                host.changed();
            });
        }

        @Override public void onCharacteristicReadRequest(BluetoothDevice d, int rid, int off, BluetoothGattCharacteristic c) {
            Ref r = refs.get(c);
            byte[] v = r == null ? null : cache.get(r.key());
            if (v == null && r != null && ble.isConnected(r.id)) {
                // value not known yet: read on the source, answered when it comes
                h.post(() -> ble.internal(r.id, "read", r.s, r.c, false, nv -> {
                    if (nv != null) cache.put(r.key(), nv);
                    respond(d, rid, off, nv == null ? new byte[0] : nv);
                }));
                return;
            }
            respond(d, rid, off, v == null ? new byte[0] : v);
        }

        void respond(BluetoothDevice d, int rid, int off, byte[] v) {
            byte[] part = off >= v.length ? new byte[0] : Arrays.copyOfRange(v, off, v.length);
            try { server.sendResponse(d, rid, BluetoothGatt.GATT_SUCCESS, off, part); } catch (Exception ignored) { }
        }

        @Override public void onDescriptorReadRequest(BluetoothDevice d, int rid, int off, BluetoothGattDescriptor de) {
            Set<BluetoothGattCharacteristic> s = subs.get(d.getAddress());
            boolean en = s != null && s.contains(de.getCharacteristic());
            try { server.sendResponse(d, rid, BluetoothGatt.GATT_SUCCESS, 0, en ? new byte[]{1, 0} : new byte[]{0, 0}); } catch (Exception ignored) { }
        }

        @Override public void onDescriptorWriteRequest(BluetoothDevice d, int rid, BluetoothGattDescriptor de, boolean prep, boolean resp, int off, byte[] v) {
            BluetoothGattCharacteristic c = de.getCharacteristic();
            boolean en = v != null && v.length > 0 && (v[0] & 3) != 0;
            if (resp) try { server.sendResponse(d, rid, BluetoothGatt.GATT_SUCCESS, 0, v); } catch (Exception ignored) { }
            h.post(() -> {
                Set<BluetoothGattCharacteristic> s = subs.get(d.getAddress());
                if (s == null) { s = new HashSet<>(); subs.put(d.getAddress(), s); }
                if (en) s.add(c); else s.remove(c);
                clients.put(d.getAddress(), d);
                Ref r = refs.get(c);
                host.log("clonage : " + label(d) + (en ? " s'abonne à " : " se désabonne de ") + short16(c.getUuid()) + (r != null ? " (" + ble.nameOf(r.id) + ")" : ""));
                host.changed();
            });
        }

        @Override public void onCharacteristicWriteRequest(BluetoothDevice d, int rid, BluetoothGattCharacteristic c, boolean prep, boolean resp, int off, byte[] v) {
            // data only: accepted but not passed on to the source device
            if (resp) try { server.sendResponse(d, rid, BluetoothGatt.GATT_SUCCESS, 0, null); } catch (Exception ignored) { }
            h.post(() -> host.log("clonage : écriture de " + label(d) + " sur " + short16(c.getUuid()) + " ignorée (données seulement)"));
        }
    };

    JSONObject state() {
        JSONObject s = new JSONObject();
        try {
            JSONArray cl = new JSONArray(), hs = new JSONArray(), cs = new JSONArray();
            for (Map.Entry<String, BluetoothDevice> e : clients.entrySet()) {
                Set<BluetoothGattCharacteristic> sb = subs.get(e.getKey());
                List<String> l = new ArrayList<>();
                if (sb != null) for (BluetoothGattCharacteristic c : sb) l.add(short16(c.getUuid()));
                cl.put(new JSONObject().put("name", label(e.getValue())).put("subs", String.join(", ", l)));
            }
            JSONArray h0 = hist(), l0 = list();
            for (int i = 0; i < l0.length(); i++) {
                String id = l0.optString(i);
                JSONObject e = histOf(id);
                cs.put(new JSONObject().put("id", id).put("name", e != null ? e.optString("name") : id).put("src", ble.isConnected(id))
                        .put("adv", advState.containsKey(id) ? advState.get(id) : "").put("known", e != null));
            }
            for (int i = 0; i < h0.length(); i++) {
                JSONObject e = h0.optJSONObject(i);
                if (e == null) continue;
                String id = e.optString("id");
                JSONArray sv = e.optJSONArray("s");
                List<String> kinds = new ArrayList<>();
                for (int k = 0; sv != null && k < sv.length(); k++) {
                    String u = short16(UUID.fromString(sv.optJSONObject(k).optString("u")));
                    String n = u.equals("0x1826") ? "tapis" : u.equals("0x180D") ? "cardio" : u.equals("0x1814") ? "foulée" : u.equals("0x1816") ? "vélo" : null;
                    if (n != null && !kinds.contains(n)) kinds.add(n);
                }
                hs.put(new JSONObject().put("id", id).put("name", e.optString("name")).put("t", e.optLong("t")).put("con", ble.isConnected(id)).put("cloned", cloned(id)).put("kinds", String.join(", ", kinds)));
            }
            int nAdv = 0;
            for (String v : advState.values()) if ("ok".equals(v)) nAdv++;
            s.put("on", on).put("adv", nAdv > 0).put("error", error).put("clients", cl).put("sent", sent).put("clones", cs).put("hist", hs)
                    .put("name", bm != null ? bm.getAdapter().getName() : "");
        } catch (Exception ignored) { }
        return s;
    }
}
