package fr.freestudio.apphikepont;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * App Hike Connexion (bridge): foreground service (permanent notification) holding the native Bluetooth (treadmill, belt)
 * and the local channel (WebSocket 127.0.0.1:47826) used by the App Hike page in Chrome.
 * If the page goes away for more than 10 s, the devices are released.
 */
public class PontService extends Service implements Ble.Out, Ble.Host, WsServer.Handler {
    static final String STOP = "fr.freestudio.apphikepont.STOP";
    static PontService instance;
    static final List<String> LOG = new ArrayList<>();
    static Runnable onChange;

    final Handler h = new Handler(Looper.getMainLooper());
    Ble ble;
    WsServer ws;
    String error = "";
    boolean page;
    final Runnable releaseAll = () -> { if (!page) { log("page absente depuis 10 s : appareils libérés"); ble.closeAll(); ble.watched.clear(); ble.filters = null; changed(); } };

    static void addLog(String s) {
        synchronized (LOG) {
            LOG.add(new SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(new Date()) + "  " + s);
            if (LOG.size() > 400) LOG.subList(0, 100).clear();
        }
    }

    @Override public void log(String s) { addLog(s); Runnable r = onChange; if (r != null) h.post(r); }

    @Override public boolean btAllowed() {
        if (Build.VERSION.SDK_INT >= 31)
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void changed() {
        h.removeCallbacks(updateNotif);
        h.postDelayed(updateNotif, 300);
        Runnable r = onChange;
        if (r != null) h.post(r);
    }

    final Runnable updateNotif = () -> {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && instance == this) nm.notify(1, notif());
    };

    String status() {
        String d = ble == null ? "" : ble.connectedNames();
        return (error.isEmpty() ? "" : error + " · ")
                + (page ? "App Hike connectée" : "En attente d'App Hike")
                + (d.isEmpty() ? "" : " · " + d);
    }

    Notification notif() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, PontService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "pont")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("App Hike Connexion active")
                .setContentText(status())
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Arrêter", stop).build())
                .build();
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("pont", "App Hike Connexion", NotificationManager.IMPORTANCE_LOW));
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(1, notif());
        ble = new Ble(this, this, this);
        ws = new WsServer(this);
        new Thread(() -> {
            try { ws.start(); }
            catch (Exception e) { h.post(() -> { error = "canal local indisponible (" + e.getMessage() + ")"; log("✗ " + error); changed(); }); }
        }, "ws-start").start();
        log("App Hike Connexion démarrée");
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && STOP.equals(i.getAction())) { stopSelf(); return START_NOT_STICKY; }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        instance = null;
        h.removeCallbacksAndMessages(null);
        if (ws != null) ws.stop();
        if (ble != null) ble.closeAll();
        log("App Hike Connexion arrêtée");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    // ------------------------------------------------------------ channel -> page
    @Override public void reply(int rq, Object value, String errName, String errMsg) {
        try {
            JSONObject o = new JSONObject().put("t", "res").put("rq", rq).put("ok", errName == null);
            if (errName == null) o.put("v", value == null ? JSONObject.NULL : value);
            else o.put("n", errName).put("e", errMsg == null ? "" : errMsg);
            event(o);
        } catch (Exception ignored) { }
    }

    @Override public void event(JSONObject e) { if (ws != null) ws.send(e.toString()); }

    // ------------------------------------------------------------ page -> channel (reader thread: posted to the main thread)
    @Override public void onOpen(String origin) {
        h.post(() -> {
            page = true;
            h.removeCallbacks(releaseAll);
            log("App Hike connectée (" + origin + ")");
            String v;
            try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { v = "?"; }
            try { event(new JSONObject().put("t", "hello").put("v", v).put("devices", ble.allowed())); } catch (Exception ignored) { }
            changed();
        });
    }

    @Override public void onClose() {
        h.post(() -> {
            page = false;
            log("App Hike déconnectée");
            h.removeCallbacks(releaseAll);
            h.postDelayed(releaseAll, 10000);
            changed();
        });
    }

    @Override public void onLog(String s) { h.post(() -> log(s)); }

    @Override public void onText(String text) {
        h.post(() -> {
            try {
                JSONObject m = new JSONObject(text);
                String f = m.optString("f");
                int rq = m.optInt("rq", -1);
                JSONArray a = m.optJSONArray("a");
                if (a == null) a = new JSONArray();
                switch (f) {
                    case "scan": ble.scan(a.optString(0, "{}"), a.optBoolean(1)); break;
                    case "allow": ble.allow(a.optString(0), a.isNull(1) ? null : a.optString(1)); break;
                    case "watch": ble.watch(a.optString(0), a.optBoolean(1)); break;
                    case "connect": ble.connect(rq, a.optString(0)); break;
                    case "disconnect": ble.disconnect(a.optString(0)); break;
                    case "op": ble.op(rq, a.optString(0), a.optString(1), a.optString(2), a.optString(3), a.optString(4), a.optBoolean(5)); break;
                    case "ping": event(new JSONObject().put("t", "pong")); break;
                    default: if (rq >= 0) reply(rq, null, "NotSupportedError", "fonction inconnue : " + f);
                }
            } catch (Exception e) { log("message illisible : " + e.getMessage()); }
        });
    }
}
