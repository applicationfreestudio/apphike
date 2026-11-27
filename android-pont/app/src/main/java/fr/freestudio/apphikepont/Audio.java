package fr.freestudio.apphikepont;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Method;
import java.util.List;

/**
 * External speaker (Bluetooth audio, A2DP). The speaker chosen once is remembered; while App Hike Connexion runs,
 * if it is paired but not connected, the connection is asked again (at start, when Bluetooth comes back, then every 30 s).
 * Android has no public call to connect an audio device: BluetoothA2dp.connect() is used by reflection; if the phone
 * refuses it, the error is shown and the Bluetooth settings can be opened instead.
 */
@SuppressLint("MissingPermission")
class Audio {
    final Context ctx; final CloneServer.Host lst; final Handler h = new Handler(Looper.getMainLooper());
    final SharedPreferences sp; final BluetoothAdapter ad;
    BluetoothA2dp a2dp; String error = ""; long lastTry = 0; boolean on;

    Audio(Context c, CloneServer.Host l) {
        ctx = c; lst = l; sp = c.getSharedPreferences("audio", Context.MODE_PRIVATE);
        BluetoothManager m = c.getSystemService(BluetoothManager.class);
        ad = m == null ? null : m.getAdapter();
    }

    final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(a) && i.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0) == BluetoothAdapter.STATE_ON) h.postDelayed(() -> tryConnect(true), 3000);
            if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(a)) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int s = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                if (d != null && chosen().contains(d.getAddress())) {
                    if (s == BluetoothProfile.STATE_CONNECTED) { error = ""; pending = ""; tried = 0; lst.log("♪ enceinte connectée : " + name(d)); }
                    else if (s == BluetoothProfile.STATE_DISCONNECTED) {
                        if (d.getAddress().equals(pending)) { pending = ""; h.postDelayed(() -> next(), 1500); }
                        else { lst.log("♪ enceinte déconnectée : " + name(d)); h.postDelayed(() -> tryConnect(true), 2000); }
                    }
                }
                lst.changed();
            }
        }
    };

    final Runnable loop = new Runnable() { @Override public void run() { tryConnect(false); h.postDelayed(this, 30000); } };
    /** Device being tried by the automatic connection, and how many chosen speakers were tried in this round. */
    String pending = ""; int tried = 0;
    final java.util.Set<String> skip = new java.util.HashSet<>();

    void start() {
        if (ad == null || on) return;
        on = true;
        IntentFilter f = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        ctx.registerReceiver(rx, f);
        try {
            ad.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int p, BluetoothProfile pr) { a2dp = (BluetoothA2dp) pr; tryConnect(true); lst.changed(); }
                @Override public void onServiceDisconnected(int p) { a2dp = null; lst.changed(); }
            }, BluetoothProfile.A2DP);
        } catch (Exception e) { error = "audio Bluetooth indisponible"; }
        h.postDelayed(loop, 30000);
    }

    void stop() {
        if (!on) return;
        on = false;
        h.removeCallbacksAndMessages(null);
        try { ctx.unregisterReceiver(rx); } catch (Exception ignored) { }
        try { if (a2dp != null) ad.closeProfileProxy(BluetoothProfile.A2DP, a2dp); } catch (Exception ignored) { }
        a2dp = null;
    }

    /** Chosen speakers, in priority order. */
    List<String> chosen() {
        List<String> l = new java.util.ArrayList<>();
        String v = sp.getString("addrs", null);
        if (v == null) v = sp.getString("addr", "");
        for (String x : v.split(",")) if (!x.isEmpty() && !l.contains(x)) l.add(x);
        return l;
    }
    void saveChosen(List<String> l) { sp.edit().putString("addrs", String.join(",", l)).remove("addr").apply(); }
    boolean auto() { return sp.getBoolean("auto", true); }
    static String name(BluetoothDevice d) { String n = null; try { n = d.getName(); } catch (Exception ignored) { } return n != null ? n : d.getAddress(); }

    static boolean isAudio(BluetoothDevice d) {
        try {
            BluetoothClass c = d.getBluetoothClass();
            if (c == null) return false;
            return c.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO || c.hasService(BluetoothClass.Service.AUDIO) || c.hasService(BluetoothClass.Service.RENDER);
        } catch (Exception e) { return false; }
    }

    BluetoothDevice dev(String addr) {
        if (ad == null || addr == null || addr.isEmpty()) return null;
        try { for (BluetoothDevice d : ad.getBondedDevices()) if (d.getAddress().equals(addr)) return d; } catch (Exception ignored) { }
        return null;
    }

    boolean connected(BluetoothDevice d) {
        try { return a2dp != null && a2dp.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED; } catch (Exception e) { return false; }
    }

    boolean connect(BluetoothDevice d) {
        if (a2dp == null) { error = "audio Bluetooth pas encore prêt"; return false; }
        try {
            Method m = BluetoothA2dp.class.getMethod("connect", BluetoothDevice.class);
            Object r = m.invoke(a2dp, d);
            if (Boolean.FALSE.equals(r)) { error = "connexion refusée par le téléphone : connecte l’enceinte dans les réglages Bluetooth"; return false; }
            error = ""; return true;
        } catch (Exception e) {
            error = "ce téléphone ne permet pas de connecter l’enceinte depuis l’app : utilise les réglages Bluetooth";
            return false;
        }
    }

    BluetoothDevice connectedChosen() {
        for (String x : chosen()) { BluetoothDevice d = dev(x); if (d != null && connected(d)) return d; }
        return null;
    }

    /** No chosen speaker connected: try them one after another (the one that answers stays), at most every 20 s unless forced. */
    void tryConnect(boolean force) {
        if (!on || !auto() || ad == null || !ad.isEnabled() || a2dp == null) return;
        if (connectedChosen() != null || !pending.isEmpty()) return;
        long n = System.currentTimeMillis();
        if (!force && n - lastTry < 20000) return;
        lastTry = n; tried = 0;
        next();
    }

    void next() {
        if (!on || !auto() || connectedChosen() != null) { tried = 0; return; }
        List<String> c = chosen(); c.removeAll(skip);
        java.util.List<BluetoothDevice> ds = new java.util.ArrayList<>();
        for (String x : c) { BluetoothDevice d = dev(x); if (d != null) ds.add(d); }
        if (tried >= ds.size()) { tried = 0; lst.changed(); return; }
        BluetoothDevice d = ds.get(tried++);
        pending = d.getAddress();
        lst.log("♪ essai de l’enceinte " + name(d) + "…");
        if (!connect(d)) { pending = ""; tried = ds.size(); }
        else h.postDelayed(() -> { if (d.getAddress().equals(pending) && !connected(d)) { pending = ""; next(); } }, 12000);
        lst.changed();
    }

    /** Add to / remove from the speakers connected automatically. */
    void toggle(String addr) {
        List<String> l = chosen();
        BluetoothDevice d = dev(addr);
        if (l.contains(addr)) { l.remove(addr); lst.log("♪ retirée de la connexion auto : " + (d != null ? name(d) : addr)); }
        else { l.add(addr); skip.remove(addr); lst.log("♪ ajoutée à la connexion auto : " + (d != null ? name(d) : addr)); }
        saveChosen(l);
        tryConnect(true);
        lst.changed();
    }

    /** Connect this speaker now (also takes it out of the skipped ones). */
    void connectNow(String addr) {
        skip.remove(addr);
        BluetoothDevice d = dev(addr);
        BluetoothDevice c = null;
        if (a2dp != null) for (BluetoothDevice x : a2dp.getConnectedDevices()) if (!x.getAddress().equals(addr)) c = x;
        if (c != null) disconnect(c);
        if (d != null) { lst.log("♪ connexion de " + name(d) + "…"); connect(d); }
        lst.changed();
    }

    /** Disconnect: not reconnected automatically until chosen again or connected by hand; the next chosen speaker is tried. */
    void disconnectNow(String addr) {
        BluetoothDevice d = dev(addr);
        if (d == null) return;
        skip.add(addr);
        lst.log("♪ déconnexion de " + name(d));
        disconnect(d);
        lst.changed();
    }

    boolean disconnect(BluetoothDevice d) {
        if (a2dp == null) return false;
        try {
            Object r = BluetoothA2dp.class.getMethod("disconnect", BluetoothDevice.class).invoke(a2dp, d);
            if (Boolean.FALSE.equals(r)) { error = "déconnexion refusée par le téléphone : utilise les réglages Bluetooth"; return false; }
            return true;
        } catch (Exception e) { error = "ce téléphone ne permet pas de déconnecter l’enceinte depuis l’app : utilise les réglages Bluetooth"; return false; }
    }

    void setAuto(boolean a) { sp.edit().putBoolean("auto", a).apply(); if (a) { skip.clear(); tryConnect(true); } lst.changed(); }

    JSONObject state() {
        JSONObject o = new JSONObject();
        try {
            List<String> c = chosen();
            BluetoothDevice cc = connectedChosen();
            o.put("on", on).put("bt", ad != null && ad.isEnabled()).put("ready", a2dp != null).put("auto", auto()).put("error", error);
            o.put("n", c.size()).put("savedCon", cc != null).put("savedName", cc != null ? name(cc) : "");
            BluetoothDevice pd = dev(pending);
            o.put("trying", pd != null ? name(pd) : "");
            String cur = "", cid = "";
            if (a2dp != null) { List<BluetoothDevice> l = a2dp.getConnectedDevices(); if (!l.isEmpty()) { cur = name(l.get(0)); cid = l.get(0).getAddress(); } }
            o.put("current", cur).put("curId", cid);
            JSONArray a = new JSONArray();
            if (ad != null) {
                List<BluetoothDevice> bs = new java.util.ArrayList<>();
                for (BluetoothDevice d : ad.getBondedDevices()) if (isAudio(d)) bs.add(d);
                bs.sort((x, y) -> { int i = c.indexOf(x.getAddress()), j = c.indexOf(y.getAddress()); return (i < 0 ? 999 : i) - (j < 0 ? 999 : j); });
                for (BluetoothDevice d : bs) a.put(new JSONObject().put("id", d.getAddress()).put("name", name(d)).put("con", connected(d))
                        .put("rank", c.indexOf(d.getAddress()) + 1).put("skip", skip.contains(d.getAddress())).put("try", d.getAddress().equals(pending)));
            }
            o.put("list", a);
        } catch (Exception ignored) { }
        return o;
    }

    String statusText() {
        BluetoothDevice d = connectedChosen();
        return d != null ? "enceinte " + name(d) : "";
    }
}
