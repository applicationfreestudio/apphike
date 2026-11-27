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
                if (d != null && d.getAddress().equals(saved())) {
                    if (s == BluetoothProfile.STATE_CONNECTED) { error = ""; lst.log("♪ enceinte connectée : " + name(d)); }
                    else if (s == BluetoothProfile.STATE_DISCONNECTED) lst.log("♪ enceinte déconnectée : " + name(d));
                }
                lst.changed();
            }
        }
    };

    final Runnable loop = new Runnable() { @Override public void run() { tryConnect(false); h.postDelayed(this, 30000); } };

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

    String saved() { return sp.getString("addr", ""); }
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

    /** Remembered speaker paired but not connected: ask the connection (at most every 20 s unless forced). */
    void tryConnect(boolean force) {
        if (!on || !auto() || ad == null || !ad.isEnabled()) return;
        BluetoothDevice d = dev(saved());
        if (d == null || connected(d)) return;
        long n = System.currentTimeMillis();
        if (!force && n - lastTry < 20000) return;
        lastTry = n;
        lst.log("♪ reconnexion de l’enceinte " + name(d) + "…");
        connect(d);
        lst.changed();
    }

    /** Choice from the list: remember it and connect it now. */
    void choose(String addr) {
        sp.edit().putString("addr", addr).apply();
        BluetoothDevice d = dev(addr);
        if (d != null) { lst.log("♪ enceinte choisie : " + name(d)); if (!connected(d)) connect(d); }
        lst.changed();
    }

    void forget() { sp.edit().remove("addr").apply(); error = ""; lst.changed(); }
    void setAuto(boolean a) { sp.edit().putBoolean("auto", a).apply(); if (a) tryConnect(true); lst.changed(); }

    JSONObject state() {
        JSONObject o = new JSONObject();
        try {
            String s = saved();
            BluetoothDevice sd = dev(s);
            o.put("on", on).put("bt", ad != null && ad.isEnabled()).put("ready", a2dp != null).put("auto", auto()).put("error", error);
            o.put("saved", s).put("savedName", sd != null ? name(sd) : sp.getString("name", "")).put("savedCon", sd != null && connected(sd)).put("savedBonded", sd != null);
            if (sd != null) sp.edit().putString("name", name(sd)).apply();
            String cur = "";
            if (a2dp != null) { List<BluetoothDevice> l = a2dp.getConnectedDevices(); if (!l.isEmpty()) cur = name(l.get(0)); }
            o.put("current", cur);
            JSONArray a = new JSONArray();
            if (ad != null) for (BluetoothDevice d : ad.getBondedDevices()) if (isAudio(d)) a.put(new JSONObject().put("id", d.getAddress()).put("name", name(d)).put("con", connected(d)));
            o.put("list", a);
        } catch (Exception ignored) { }
        return o;
    }

    String statusText() {
        BluetoothDevice d = dev(saved());
        return d != null && connected(d) ? "enceinte " + name(d) : "";
    }
}
