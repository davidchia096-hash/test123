package app.mockgps;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Hosts the map UI (a local web page in assets/) and bridges it to the mock service.
 */
public class MainActivity extends Activity implements MockState.Listener {

    /** Assets are served from a fake https origin so map tiles get a proper Referer. */
    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + ASSET_HOST + "/assets/index.html";
    private static final String USER_AGENT_SUFFIX = " MockGPS/1.0 (Android)";

    private static final int REQ_START = 1;
    private static final int REQ_LOCATE = 2;
    private static final String PREFS = "mockgps";
    private static final String PREF_ASKED_NOTIFICATIONS = "askedNotifications";

    private WebView webView;
    private Runnable pendingStart;
    private boolean pageReady;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        // Android 15+ draws apps edge-to-edge: keep the page clear of system bars and the keyboard.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (Build.VERSION.SDK_INT >= 35) root.setOnApplyWindowInsetsListener(this::applyInsets);
        setContentView(root);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setGeolocationEnabled(false);
        ws.setUserAgentString(ws.getUserAgentString() + USER_AGENT_SUFFIX);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new AssetClient());
        webView.addJavascriptInterface(new Bridge(), "Android");

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(START_URL);
        }
    }

    /** Only used on Android 15+, where edge-to-edge is enforced; older versions lay out normally. */
    @TargetApi(30)
    private WindowInsets applyInsets(View v, WindowInsets insets) {
        Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
        v.setPadding(i.left, i.top, i.right, i.bottom);
        return WindowInsets.CONSUMED;
    }

    @Override
    protected void onResume() {
        super.onResume();
        MockState.setListener(this);
        if (pageReady) sendResumeInfo();
    }

    @Override
    protected void onPause() {
        MockState.setListener(null);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("Android");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (webView == null || !pageReady) {
            super.onBackPressed();
            return;
        }
        webView.evaluateJavascript("window.handleBack ? window.handleBack() : false", value -> {
            if (!"true".equals(value)) {
                // Keep the activity (and its state) around; the service keeps running anyway.
                moveTaskToBack(true);
            }
        });
    }

    // ---------------------------------------------------------------------------------
    // Service state -> UI

    @Override
    public void onStateChanged(MockState state) {
        if (!pageReady) return; // delivered (with any error) by sendResumeInfo() once the page loads
        callJs("onNativeState", stateJsonAndClearError());
    }

    private String stateJsonAndClearError() {
        MockState s = MockState.get();
        String json = s.toJson();
        s.error = null;
        return json;
    }

    private void sendResumeInfo() {
        JSONObject o = new JSONObject();
        try {
            o.put("mockAppSelected", isMockAppSelected());
            o.put("hasLocationPermission", hasLocationPermission());
            o.put("state", new JSONObject(stateJsonAndClearError()));
        } catch (JSONException ignored) {
            // Not reachable with these values.
        }
        callJs("onNativeResume", o.toString());
    }

    private void callJs(String fn, String jsonArg) {
        if (webView == null || !pageReady) return;
        webView.evaluateJavascript("window." + fn + " && window." + fn + "(" + jsonArg + ")", null);
    }

    // ---------------------------------------------------------------------------------
    // Permissions & checks

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("deprecation")
    private boolean isMockAppSelected() {
        AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        if (ops == null) return false;
        try {
            int mode = Build.VERSION.SDK_INT >= 29
                    ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), getPackageName())
                    : ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Runs {@code action} once location permission is granted (asks if needed). */
    private void withPermissions(Runnable action) {
        List<String> need = new ArrayList<>();
        if (!hasLocationPermission()) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean(PREF_ASKED_NOTIFICATIONS, false)) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
            prefs.edit().putBoolean(PREF_ASKED_NOTIFICATIONS, true).apply();
        }
        if (need.isEmpty()) {
            action.run();
            return;
        }
        pendingStart = action;
        requestPermissions(need.toArray(new String[0]), REQ_START);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = hasLocationPermission();
        if (requestCode == REQ_START) {
            Runnable action = pendingStart;
            pendingStart = null;
            if (granted && action != null) {
                action.run();
            } else if (!granted) {
                callJs("onPermissionResult", "{\"granted\":false,\"forStart\":true}");
            }
        } else if (requestCode == REQ_LOCATE) {
            callJs("onPermissionResult", "{\"granted\":" + granted + ",\"forStart\":false}");
        }
    }

    private void startMockService(Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void sendToService(String action) {
        if (!MockState.get().isRunning()) return;
        startService(new Intent(this, MockLocationService.class).setAction(action));
    }

    private void openDeveloperOptions() {
        Intent[] candidates = {
                new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
                new Intent(Settings.ACTION_SETTINGS),
        };
        for (Intent i : candidates) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException ignored) {
                // Try the next screen.
            }
        }
    }

    @SuppressLint("MissingPermission")
    private String lastKnownLocationJson() {
        if (!hasLocationPermission()) return "{\"error\":\"permission\"}";
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location best = null;
        List<String> names = lm == null ? Collections.<String>emptyList() : lm.getProviders(true);
        for (String p : names) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (RuntimeException ignored) {
                // Provider not readable; skip.
            }
        }
        if (best == null) return "{\"error\":\"unavailable\"}";
        JSONObject o = new JSONObject();
        try {
            o.put("lat", best.getLatitude());
            o.put("lng", best.getLongitude());
            o.put("accuracy", best.hasAccuracy() ? best.getAccuracy() : 0);
        } catch (JSONException ignored) {
            // Not reachable with finite coordinates.
        }
        return o.toString();
    }

    // ---------------------------------------------------------------------------------
    // JavaScript bridge (methods run on a WebView background thread)

    private final class Bridge {

        @JavascriptInterface
        public void ready() {
            runOnUiThread(() -> {
                pageReady = true;
                sendResumeInfo();
            });
        }

        @JavascriptInterface
        public String getState() {
            return MockState.get().toJson();
        }

        @JavascriptInterface
        public boolean isMockAppSelected() {
            return MainActivity.this.isMockAppSelected();
        }

        @JavascriptInterface
        public void startPoint(double lat, double lng, boolean drift) {
            Intent i = new Intent(MainActivity.this, MockLocationService.class)
                    .setAction(MockLocationService.ACTION_START_POINT)
                    .putExtra(MockLocationService.EXTRA_LAT, lat)
                    .putExtra(MockLocationService.EXTRA_LNG, lng)
                    .putExtra(MockLocationService.EXTRA_DRIFT, drift);
            runOnUiThread(() -> withPermissions(() -> startMockService(i)));
        }

        @JavascriptInterface
        public void startRoute(String pointsJson, double speedKmh, boolean loop, boolean drift) {
            double[] lats;
            double[] lngs;
            try {
                JSONArray pts = new JSONArray(pointsJson);
                lats = new double[pts.length()];
                lngs = new double[pts.length()];
                for (int k = 0; k < pts.length(); k++) {
                    JSONArray p = pts.getJSONArray(k);
                    lats[k] = p.getDouble(0);
                    lngs[k] = p.getDouble(1);
                }
            } catch (JSONException e) {
                return;
            }
            if (lats.length < 2) return;
            Intent i = new Intent(MainActivity.this, MockLocationService.class)
                    .setAction(MockLocationService.ACTION_START_ROUTE)
                    .putExtra(MockLocationService.EXTRA_ROUTE_LATS, lats)
                    .putExtra(MockLocationService.EXTRA_ROUTE_LNGS, lngs)
                    .putExtra(MockLocationService.EXTRA_SPEED_KMH, speedKmh)
                    .putExtra(MockLocationService.EXTRA_LOOP, loop)
                    .putExtra(MockLocationService.EXTRA_DRIFT, drift);
            runOnUiThread(() -> withPermissions(() -> startMockService(i)));
        }

        @JavascriptInterface
        public void togglePause() {
            runOnUiThread(() -> sendToService(MockLocationService.ACTION_TOGGLE_PAUSE));
        }

        @JavascriptInterface
        public void stop() {
            runOnUiThread(() -> sendToService(MockLocationService.ACTION_STOP));
        }

        @JavascriptInterface
        public void openDeveloperOptions() {
            runOnUiThread(MainActivity.this::openDeveloperOptions);
        }

        @JavascriptInterface
        public String getLastKnownLocation() {
            return lastKnownLocationJson();
        }

        @JavascriptInterface
        public void requestLocationPermission() {
            runOnUiThread(() -> requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATE));
        }

        @JavascriptInterface
        public void copyText(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("coordinates", text));
                if (Build.VERSION.SDK_INT < 33) {
                    // Android 13+ shows its own clipboard confirmation.
                    Toast.makeText(MainActivity.this, R.string.copied, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void openUrl(String url) {
            runOnUiThread(() -> openExternal(Uri.parse(url)));
        }
    }

    private void openExternal(Uri uri) {
        String scheme = uri.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme)) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
            // No browser installed.
        }
    }

    // ---------------------------------------------------------------------------------
    // Serves assets/ at https://appassets.androidplatform.net/assets/...

    private final class AssetClient extends WebViewClient {

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            String path = url.getPath();
            if (!ASSET_HOST.equals(url.getHost()) || path == null || !path.startsWith("/assets/")) {
                return null; // real network request (map tiles, search)
            }
            String asset = path.substring("/assets/".length());
            try {
                InputStream in = getAssets().open(asset);
                return new WebResourceResponse(mimeType(asset), "UTF-8", in);
            } catch (IOException e) {
                return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                        Collections.<String, String>emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (ASSET_HOST.equals(url.getHost())) return false;
            openExternal(url);
            return true;
        }
    }

    private static String mimeType(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".js")) return "application/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }
}
