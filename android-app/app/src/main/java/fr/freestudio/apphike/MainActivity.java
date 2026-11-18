package fr.freestudio.apphike;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * App Hike for Android: the App Hike page (served by the Cloudflare Worker) in a WebView, with native Bluetooth
 * (treadmill and heart-rate belt) exposed to the page as Web Bluetooth by the polyfill at the top of index.html.
 * The page keeps updating through Cloudflare; this APK only needs reinstalling when its native part changes.
 */
public class MainActivity extends Activity implements Ble.Out {
    static final String TAG = "AppHike", DEFAULT_URL = "https://apphike.david-ozon.workers.dev/";
    final Handler h = new Handler(Looper.getMainLooper());
    WebView web;
    Ble ble;
    SharedPreferences prefs;
    ValueCallback<Uri[]> fileCb;
    boolean fullscreen, loadFailed;
    int notifRq = -1;
    String barColor = "#F6F5F1";

    void log(String s) { Log.i(TAG, s); }

    // ------------------------------------------------------------ page <-> native
    @Override public void reply(int rq, Object value, String errName, String errMsg) {
        try {
            JSONObject o = new JSONObject().put("t", "res").put("rq", rq).put("ok", errName == null);
            if (errName == null) o.put("v", value == null ? JSONObject.NULL : value);
            else o.put("n", errName).put("e", errMsg == null ? "" : errMsg);
            event(o);
        } catch (Exception ignored) { }
    }

    @Override public void event(JSONObject e) {
        String js = "window.__ahn&&window.__ahn(" + e + ")";
        h.post(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    /** Methods called by the page (window.AHN). They run on a WebView thread: the work is posted to the main thread. */
    class Bridge {
        @JavascriptInterface public String version() { try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; } }
        @JavascriptInterface public void choose(int rq, String filters) { h.post(() -> ble.choose(rq, filters)); }
        @JavascriptInterface public String devices() { return ble.allowed().toString(); }
        @JavascriptInterface public void watch(String id, boolean on) { h.post(() -> ble.watch(id, on)); }
        @JavascriptInterface public void connect(int rq, String id) { h.post(() -> ble.connect(rq, id)); }
        @JavascriptInterface public void disconnect(String id) { h.post(() -> ble.disconnect(id)); }
        @JavascriptInterface public void op(int rq, String id, String kind, String s, String c, String hex, boolean flag) { h.post(() -> ble.op(rq, id, kind, s, c, hex, flag)); }
        @JavascriptInterface public boolean saveFile(String name, String text) { return MainActivity.this.saveFile(name, text); }
        @JavascriptInterface public String notifPerm() { return MainActivity.this.notifPerm(); }
        @JavascriptInterface public void notifAsk(int rq) { h.post(() -> MainActivity.this.notifAsk(rq)); }
        @JavascriptInterface public void notify(String title, String body) { h.post(() -> MainActivity.this.notify(title, body)); }
        @JavascriptInterface public void fullscreen(boolean on) { h.post(() -> setFullscreen(on)); }
        @JavascriptInterface public void orient(String o) { h.post(() -> setRequestedOrientation(o.startsWith("landscape") ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : o.startsWith("portrait") ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)); }
        @JavascriptInterface public void keepScreen(boolean on) { h.post(() -> { if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }); }
        @JavascriptInterface public void vibrate(String json) { h.post(() -> MainActivity.this.vibrate(json)); }
        @JavascriptInterface public void bars(String color) { h.post(() -> setBars(color)); }
        @JavascriptInterface public void changeUrl() { h.post(() -> askUrl(false)); }
    }

    // ------------------------------------------------------------ setup
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("app", MODE_PRIVATE);
        ble = new Ble(this, this);
        web = new WebView(this);
        setContentView(web);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(false);
        ws.setTextZoom(100);
        ws.setUserAgentString(ws.getUserAgentString() + " AppHikeAPK");
        web.addJavascriptInterface(new Bridge(), "AHN");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (u.getHost() != null && u.getHost().equals(Uri.parse(url()).getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) loadError("La page ne répond pas (" + e.getDescription() + ").");
            }
            @Override public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse e) {
                if (r.isForMainFrame()) loadError("L'adresse répond « " + e.getStatusCode() + " » : ce n'est sans doute pas la bonne.");
            }
            @Override public void onPageFinished(WebView v, String u) { loadFailed = false; }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                try { startActivityForResult(Intent.createChooser(i, "Choisir le fichier de sauvegarde"), 2); } catch (Exception e) { fileCb = null; return false; }
                return true;
            }
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage m) { Log.d(TAG, "page: " + m.message()); return true; }
        });
        setBars(barColor);
        askBluetoothPermissions();
        if (prefs.contains("url")) web.loadUrl(url()); else askUrl(true);
    }

    String url() { return prefs.getString("url", DEFAULT_URL); }

    /** App Hike address (the Cloudflare Worker): asked at first launch, and when the page cannot be loaded. */
    void askUrl(boolean first) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setText(url());
        e.setSelectAllOnFocus(true);
        int p = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout f = new android.widget.FrameLayout(this);
        f.setPadding(p, p / 2, p, 0);
        f.addView(e);
        new AlertDialog.Builder(this)
                .setTitle("Adresse d'App Hike")
                .setMessage(first ? "L'adresse de ton App Hike sur Cloudflare, celle que tu ouvres dans Chrome." : "Adresse de ton App Hike sur Cloudflare.")
                .setView(f)
                .setCancelable(!first)
                .setPositiveButton("Ouvrir", (d, w) -> {
                    String u = e.getText().toString().trim();
                    if (!u.startsWith("http")) u = "https://" + u;
                    if (!u.endsWith("/")) u += "/";
                    prefs.edit().putString("url", u).apply();
                    loadFailed = false;
                    web.loadUrl(u);
                })
                .show();
    }

    void loadError(String why) {
        if (loadFailed) return;
        loadFailed = true;
        new AlertDialog.Builder(this)
                .setTitle("App Hike ne se charge pas")
                .setMessage(why + "\nAdresse : " + url())
                .setPositiveButton("Réessayer", (d, w) -> { loadFailed = false; web.loadUrl(url()); })
                .setNeutralButton("Changer l'adresse", (d, w) -> askUrl(false))
                .setCancelable(false)
                .show();
    }

    // ------------------------------------------------------------ permissions
    boolean btAllowed() {
        if (Build.VERSION.SDK_INT >= 31)
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    void askBluetoothPermissions() {
        if (btAllowed()) return;
        if (Build.VERSION.SDK_INT >= 31) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, 1);
        else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
    }

    void askBluetoothOn() {
        try { startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); } catch (Exception ignored) { }
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (rc == 3 && notifRq >= 0) { prefs.edit().putBoolean("notifAsked", true).apply(); reply(notifRq, notifPerm(), null, null); notifRq = -1; }
    }

    // ------------------------------------------------------------ notifications
    String notifPerm() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        boolean perm = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        if (perm && nm.areNotificationsEnabled()) return "granted";
        if (Build.VERSION.SDK_INT >= 33 && !perm && !prefs.getBoolean("notifAsked", false)) return "default";
        return "denied";
    }

    void notifAsk(int rq) {
        if (Build.VERSION.SDK_INT >= 33 && "default".equals(notifPerm())) { notifRq = rq; requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3); }
        else reply(rq, notifPerm(), null, null);
    }

    void notify(String title, String body) {
        if (!"granted".equals(notifPerm())) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("tapis", "Tapis connecté", NotificationManager.IMPORTANCE_HIGH));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "tapis").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title).setContentText(body).setContentIntent(pi).setAutoCancel(true).build();
        nm.notify(1, n);
    }

    // ------------------------------------------------------------ files
    /** Backup file from the page (blob download): saved in Downloads. */
    boolean saveFile(String name, String text) {
        String n = name == null || name.isEmpty() ? "apphike.json" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, n);
                v.put(MediaStore.Downloads.MIME_TYPE, n.endsWith(".json") ? "application/json" : "application/octet-stream");
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u == null) return false;
                try (OutputStream o = getContentResolver().openOutputStream(u)) { if (o == null) return false; o.write(data); }
            } else {
                File d = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                try (FileOutputStream o = new FileOutputStream(new File(d, n))) { o.write(data); }
            }
            h.post(() -> Toast.makeText(this, "Enregistré dans Téléchargements : " + n, Toast.LENGTH_LONG).show());
            return true;
        } catch (Exception e) {
            h.post(() -> Toast.makeText(this, "Enregistrement impossible : " + e.getMessage(), Toast.LENGTH_LONG).show());
            return false;
        }
    }

    @Override protected void onActivityResult(int rc, int res, Intent data) {
        if (rc == 2 && fileCb != null) {
            Uri u = res == RESULT_OK && data != null ? data.getData() : null;
            fileCb.onReceiveValue(u != null ? new Uri[]{u} : null);
            fileCb = null;
        }
    }

    // ------------------------------------------------------------ screen
    void setFullscreen(boolean on) {
        fullscreen = on;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            if (on) { c.hide(WindowInsets.Type.systemBars()); c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
            else c.show(WindowInsets.Type.systemBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(on ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE : 0);
            setBars(barColor);
        }
    }

    /** System bars in the page colour (the theme colour when the page sets one, otherwise its background). */
    void setBars(String color) {
        int c;
        try { c = parseColor(color); } catch (Exception e) { return; }
        barColor = color;
        getWindow().setStatusBarColor(c);
        getWindow().setNavigationBarColor(c);
        boolean light = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) > 150;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController w = getWindow().getInsetsController();
            if (w != null) {
                int m = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                w.setSystemBarsAppearance(light ? m : 0, m);
            }
        } else if (!fullscreen) {
            getWindow().getDecorView().setSystemUiVisibility(light ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
        }
    }

    static int parseColor(String s) {
        s = s.trim();
        if (s.startsWith("rgb")) {
            String[] p = s.substring(s.indexOf('(') + 1, s.indexOf(')')).split(",");
            return Color.rgb(Math.round(Float.parseFloat(p[0].trim())), Math.round(Float.parseFloat(p[1].trim())), Math.round(Float.parseFloat(p[2].trim())));
        }
        return Color.parseColor(s);
    }

    void vibrate(String json) {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null) return;
        try {
            Object o = new JSONArray("[" + json + "]").get(0);
            if (o instanceof JSONArray) {
                JSONArray a = (JSONArray) o;
                long[] t = new long[a.length() + 1];
                for (int i = 0; i < a.length(); i++) t[i + 1] = a.getLong(i);
                v.vibrate(VibrationEffect.createWaveform(t, -1));
            } else {
                long ms = ((Number) o).longValue();
                if (ms > 0) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)); else v.cancel();
            }
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ lifecycle
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (fullscreen) { web.evaluateJavascript("document.exitFullscreen&&document.exitFullscreen()", null); return; }
        // the page has no history: back puts the app in the background without stopping it (treadmill stays connected)
        moveTaskToBack(true);
    }

    @Override protected void onDestroy() {
        ble.closeAll();
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
