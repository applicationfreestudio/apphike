package fr.freestudio.apphikepont;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Bridge screen: permissions, start/stop, status and log. The bridge itself is PontService. */
public class MainActivity extends Activity {
    final Handler h = new Handler(Looper.getMainLooper());
    TextView tvState, tvLog;
    Button bRun;
    ScrollView sv;

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(32), dp(16), dp(12));
        TextView t = new TextView(this);
        t.setText("App Hike Connexion");
        t.setTextSize(22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(t);
        TextView help = new TextView(this);
        help.setText("Laisse App Hike Connexion active (notification « App Hike Connexion active »), puis ouvre App Hike dans Chrome avec l'option « Se connecter par App Hike Connexion » cochée. Le tapis et la ceinture se connectent ici, à la demande d'App Hike.");
        help.setTextSize(14);
        help.setPadding(0, dp(8), 0, dp(8));
        root.addView(help);
        tvState = new TextView(this);
        tvState.setTextSize(16);
        tvState.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(tvState);
        bRun = new Button(this);
        bRun.setAllCaps(false);
        bRun.setOnClickListener(v -> { if (PontService.instance != null) stopService(new Intent(this, PontService.class)); else start(); h.postDelayed(this::ui, 400); });
        root.addView(bRun);
        TextView lt = new TextView(this);
        lt.setText("Journal");
        lt.setAlpha(.7f);
        lt.setPadding(0, dp(10), 0, 0);
        root.addView(lt);
        sv = new ScrollView(this);
        tvLog = new TextView(this);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextSize(10.5f);
        tvLog.setTextIsSelectable(true);
        sv.addView(tvLog);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        PontService.onChange = this::ui;
        start();
        backToChrome(getIntent());
    }

    /** Opened by App Hike's "Lancer App Hike Connexion" button: once the service runs, go back to Chrome. */
    void backToChrome(Intent i) {
        if (i == null || !Intent.ACTION_VIEW.equals(i.getAction()) || i.getData() == null || !"apphikeconnexion".equals(i.getData().getScheme())) return;
        setIntent(new Intent(this, MainActivity.class));
        if (needed().length == 0 || btGranted()) h.postDelayed(() -> moveTaskToBack(true), 600);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (PontService.instance == null) start();
        backToChrome(i);
    }

    @Override protected void onResume() { super.onResume(); PontService.onChange = this::ui; ui(); }
    @Override protected void onDestroy() { PontService.onChange = null; super.onDestroy(); }

    String[] needed() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) { p.add(Manifest.permission.BLUETOOTH_SCAN); p.add(Manifest.permission.BLUETOOTH_CONNECT); }
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
        h.postDelayed(this::ui, 400);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (btGranted()) { startForegroundService(new Intent(this, PontService.class)); h.postDelayed(this::ui, 400); }
        else { PontService.addLog("autorisation « Appareils à proximité » refusée : App Hike Connexion ne peut pas fonctionner"); ui(); }
    }

    void ui() {
        PontService s = PontService.instance;
        tvState.setText(s == null ? "○ App Hike Connexion arrêtée" : "● " + s.status());
        tvState.setTextColor(s == null ? 0xFF9E9E9E : s.page ? 0xFF43A047 : 0xFFFB8C00);
        bRun.setText(s == null ? "Démarrer" : "Arrêter");
        StringBuilder b = new StringBuilder();
        synchronized (PontService.LOG) { for (String l : PontService.LOG) b.append(l).append('\n'); }
        tvLog.setText(b);
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
    }
}
