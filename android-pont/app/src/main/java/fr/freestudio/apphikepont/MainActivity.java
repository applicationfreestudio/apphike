package fr.freestudio.apphikepont;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.view.View;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * App Hike Connexion screen, with the App Hike look (local page assets/ui.html): connection state, watch emission
 * and its test options, log. The bridge and the watch server run in PontService.
 */
public class MainActivity extends Activity {
    final Handler h = new Handler(Looper.getMainLooper());
    WebView web;
    SharedPreferences wp;

    boolean dark() { return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES; }

    void bars() {
        int c = dark() ? Color.parseColor("#171815") : Color.parseColor("#F6F5F1");
        getWindow().setStatusBarColor(c);
        getWindow().setNavigationBarColor(c);
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            int m = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(dark() ? 0 : m, m);
        } else getWindow().getDecorView().setSystemUiVisibility(dark() ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    class Bridge {
        @JavascriptInterface public String state() {
            try {
                PontService s = PontService.instance;
                JSONObject st = new JSONObject();
                String v;
                try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { v = "?"; }
                st.put("version", v);
                st.put("pont", new JSONObject().put("on", s != null).put("page", s != null && s.page).put("devices", s != null && s.ble != null ? s.ble.connectedNames() : "").put("error", s != null ? s.error : ""));
                st.put("watchOn", wp.getBoolean("on", false));
                st.put("watch", s != null && s.watch != null ? s.watch.state() : new JSONObject().put("on", false));
                StringBuilder b = new StringBuilder();
                synchronized (PontService.LOG) { for (String l : PontService.LOG) b.append(l).append('\n'); }
                st.put("log", b.toString());
                st.put("perm", btGranted());
                st.put("logOn", PontService.LOG_ON);
                return st.toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public void pont(boolean on) { h.post(() -> { if (on) start(); else stopService(new Intent(MainActivity.this, PontService.class)); }); }
        @JavascriptInterface public void watch(boolean on) {
            h.post(() -> {
                wp.edit().putBoolean("on", on).apply();
                PontService s = PontService.instance;
                if (on) { if (s == null) start(); else if (s.watch != null) s.watch.start(); }
                else if (s != null && s.watch != null) s.watch.stop();
            });
        }
        @JavascriptInterface public void cloneAdd(String id) { h.post(() -> { PontService s = PontService.instance; if (s != null && s.watch != null) s.watch.add(id); }); }
        @JavascriptInterface public void cloneDel(String id) { h.post(() -> { PontService s = PontService.instance; if (s != null && s.watch != null) s.watch.del(id); }); }
        @JavascriptInterface public void cloneForget(String id) { h.post(() -> { PontService s = PontService.instance; if (s != null && s.watch != null) s.watch.forget(id); }); }
        @JavascriptInterface public void copy(String text) {
            h.post(() -> {
                ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("journal", text));
                Toast.makeText(MainActivity.this, "Journal copié", Toast.LENGTH_SHORT).show();
            });
        }
        @JavascriptInterface public void share(String text) {
            h.post(() -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Partager le journal")));
        }
        @JavascriptInterface public void logOn(boolean on) { PontService.LOG_ON = on; getSharedPreferences("journal", MODE_PRIVATE).edit().putBoolean("on", on).apply(); }
        @JavascriptInterface public void clearLog() { synchronized (PontService.LOG) { PontService.LOG.clear(); } }
        @JavascriptInterface public void perms() { h.post(MainActivity.this::start); }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        PontService.loadLogOn(this);
        wp = getSharedPreferences("watch", MODE_PRIVATE);
        bars();
        web = new WebView(this);
        web.setBackgroundColor(dark() ? Color.parseColor("#171815") : Color.parseColor("#F6F5F1"));
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "AHC");
        web.setWebViewClient(new WebViewClient());
        String sp = splashHash(getIntent());
        web.loadUrl("file:///android_asset/ui.html" + (sp != null ? sp : getIntent() != null && getIntent().getBooleanExtra("complet", false) ? "#complet" : ""));
        setContentView(web);
        start();
        backToChrome(getIntent());
    }

    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); bars(); }

    static boolean fromAppHike(Intent i) { return i != null && Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null && "apphikeconnexion".equals(i.getData().getScheme()); }

    /** Short welcome screen when opened by App Hike: logo and the devices chosen in App Hike (names passed in the link). */
    String splashHash(Intent i) {
        if (!fromAppHike(i) || needed().length > 0) return null;
        Uri u = i.getData();
        String tm = u.getQueryParameter("tm"), hr = u.getQueryParameter("hr");
        return "#splash&tm=" + Uri.encode(tm == null ? "" : tm) + "&hr=" + Uri.encode(hr == null ? "" : hr);
    }

    /** Opened by App Hike's "Lancer App Hike Connexion" button: welcome screen for 2.5 s, then back to Chrome. */
    void backToChrome(Intent i) {
        if (!fromAppHike(i)) return;
        setIntent(new Intent(this, MainActivity.class));
        if (needed().length > 0) return;
        h.postDelayed(() -> {
            moveTaskToBack(true);
            h.postDelayed(() -> { if (web != null) web.evaluateJavascript("window.splashOff&&splashOff()", null); }, 400);
        }, 2500);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i != null && i.getBooleanExtra("complet", false) && web != null) web.evaluateJavascript("window.complet&&complet()", null);
        String sp = splashHash(i);
        if (sp != null && web != null) web.evaluateJavascript("window.splashOn&&splashOn(" + JSONObject.quote(sp) + ")", null);
        if (PontService.instance == null) start();
        backToChrome(i);
    }

    String[] needed() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) { p.add(Manifest.permission.BLUETOOTH_SCAN); p.add(Manifest.permission.BLUETOOTH_CONNECT); p.add(Manifest.permission.BLUETOOTH_ADVERTISE); }
        else p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
        List<String> miss = new ArrayList<>();
        for (String s : p) if (checkSelfPermission(s) != PackageManager.PERMISSION_GRANTED) miss.add(s);
        return miss.toArray(new String[0]);
    }

    boolean btGranted() {
        if (Build.VERSION.SDK_INT >= 31) return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    void start() {
        String[] miss = needed();
        if (miss.length > 0) { requestPermissions(miss, 1); return; }
        BluetoothAdapter a = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        if (a != null && !a.isEnabled()) { try { startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); } catch (Exception ignored) { } }
        startForegroundService(new Intent(this, PontService.class));
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (btGranted()) startForegroundService(new Intent(this, PontService.class));
        else PontService.addLog("autorisation « Appareils à proximité » refusée : App Hike Connexion ne peut pas fonctionner");
    }

    @Override protected void onDestroy() { if (web != null) web.destroy(); super.onDestroy(); }
}
