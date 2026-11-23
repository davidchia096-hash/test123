package app.mockgps;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Shows the Google Maps website so the user can search Google's places of interest.
 * When they open a place, its coordinates are read from the page address and handed
 * back to {@link MainActivity}. No API key needed.
 */
public class GoogleMapsPickerActivity extends Activity {

    public static final String EXTRA_URL = "url";
    /** Return as soon as an exact place is found (used for links shared from Google Maps). */
    public static final String EXTRA_AUTO_PICK = "autoPick";
    /** Name to use if the link itself doesn't carry one. */
    public static final String EXTRA_NAME_HINT = "nameHint";

    public static final String RESULT_LAT = "lat";
    public static final String RESULT_LNG = "lng";
    public static final String RESULT_NAME = "name";
    public static final String RESULT_PRECISE = "precise";

    private static final String HOME_URL = "https://www.google.com/maps";
    private static final long POLL_MS = 400;
    private static final long AUTO_PICK_DELAY_MS = 900;
    private static final int INK = 0xFF16201C;
    private static final int MUTED = 0xFF5F6B66;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private WebView webView;
    private ProgressBar progress;
    private TextView status;
    private Button useButton;

    private String lastUrl;
    private MapsLinkParser.Place current;
    private boolean autoPick;
    private String nameHint;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        autoPick = getIntent().getBooleanExtra(EXTRA_AUTO_PICK, false);
        nameHint = getIntent().getStringExtra(EXTRA_NAME_HINT);

        setContentView(buildLayout());

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        // Present as regular Chrome so Google serves its full mobile map site.
        ws.setUserAgentString(ws.getUserAgentString()
                .replace("; wv)", ")")
                .replaceAll(" Version/\\d+(\\.\\d+)*", ""));

        webView.setWebViewClient(new PickerClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });

        String url = getIntent().getStringExtra(EXTRA_URL);
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(url == null || url.isEmpty() ? HOME_URL : url);
        }
        updateUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ---------------------------------------------------------------------------------
    // Watching the page address

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            checkUrl();
            handler.postDelayed(this, POLL_MS);
        }
    };

    private void checkUrl() {
        if (webView == null) return;
        String url = webView.getUrl();
        if (url == null || url.equals(lastUrl)) return;
        lastUrl = url;
        setCurrent(MapsLinkParser.parseUrl(url));
    }

    private void setCurrent(MapsLinkParser.Place p) {
        if (p != null && p.name == null && p.precise && nameHint != null) p = p.withName(nameHint);
        current = p;
        updateUi();
        if (autoPick && p != null && p.precise) {
            autoPick = false;
            handler.postDelayed(this::deliver, AUTO_PICK_DELAY_MS);
        }
    }

    private void deliver() {
        MapsLinkParser.Place p = current;
        if (p == null || isFinishing()) return;
        Intent data = new Intent()
                .putExtra(RESULT_LAT, p.lat)
                .putExtra(RESULT_LNG, p.lng)
                .putExtra(RESULT_NAME, p.name)
                .putExtra(RESULT_PRECISE, p.precise);
        setResult(RESULT_OK, data);
        finish();
    }

    private void updateUi() {
        MapsLinkParser.Place p = current;
        if (p == null) {
            status.setText(R.string.picker_hint);
            status.setTextColor(MUTED);
            useButton.setEnabled(false);
            useButton.setAlpha(0.4f);
            useButton.setText(R.string.picker_use_place);
            return;
        }
        String coords = String.format(Locale.US, "%.6f, %.6f", p.lat, p.lng);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (p.precise) {
            String title = p.name != null ? p.name : getString(R.string.picker_selected_place);
            sb.append(title);
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append('\n').append(coords);
            useButton.setText(R.string.picker_use_place);
        } else {
            String title = getString(R.string.picker_map_centre);
            sb.append(title).append(" · ").append(coords);
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append('\n').append(getString(R.string.picker_tap_for_exact));
            useButton.setText(R.string.picker_use_centre);
        }
        status.setText(sb);
        status.setTextColor(INK);
        useButton.setEnabled(true);
        useButton.setAlpha(1f);
    }

    // ---------------------------------------------------------------------------------
    // Keep everything inside this screen: no jumping out to the Google Maps app.

    private final class PickerClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (scheme.equals("http") || scheme.equals("https")) return false;
            if (scheme.equals("geo")) {
                MapsLinkParser.Place p = MapsLinkParser.parseGeo(uri.toString());
                if (p != null) setCurrent(p);
            } else if (scheme.equals("intent")) {
                try {
                    Intent i = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                    String fallback = i.getStringExtra("browser_fallback_url");
                    if (fallback != null && fallback.startsWith("http")) view.loadUrl(fallback);
                } catch (URISyntaxException ignored) {
                    // Not a link we can follow in here.
                }
            }
            return true;
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            checkUrl();
        }
    }

    // ---------------------------------------------------------------------------------
    // Layout (built in code; the app has no XML layouts)

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= 35) root.setOnApplyWindowInsetsListener(GoogleMapsPickerActivity::fitSystemBars);

        // Top bar
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), 0, dp(16), 0);
        ImageButton close = new ImageButton(this);
        close.setImageResource(R.drawable.ic_close);
        close.setBackground(null);
        close.setContentDescription(getString(R.string.picker_close));
        close.setOnClickListener(v -> finish());
        top.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = new TextView(this);
        title.setText(R.string.picker_title);
        title.setTextColor(INK);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        // Map page with a thin loading bar on top
        FrameLayout mapFrame = new FrameLayout(this);
        webView = new WebView(this);
        mapFrame.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setIndeterminate(false);
        mapFrame.addView(progress, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4), Gravity.TOP));
        root.addView(mapFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Bottom panel
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(16), dp(12), dp(16), dp(16));
        bottom.setBackgroundColor(Color.WHITE);
        bottom.setElevation(dp(8));
        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setLineSpacing(0, 1.15f);
        status.setMaxLines(3);
        bottom.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        useButton = new Button(this);
        useButton.setAllCaps(false);
        useButton.setTextColor(Color.WHITE);
        useButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        useButton.setTypeface(Typeface.DEFAULT_BOLD);
        useButton.setStateListAnimator(null);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.accent));
        bg.setCornerRadius(dp(15));
        useButton.setBackground(bg);
        useButton.setOnClickListener(v -> deliver());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(12);
        bottom.addView(useButton, lp);
        root.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    /** Android 15+ draws edge-to-edge: pad content away from system bars and the keyboard. */
    @TargetApi(30)
    static WindowInsets fitSystemBars(View v, WindowInsets insets) {
        Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
        v.setPadding(i.left, i.top, i.right, i.bottom);
        return WindowInsets.CONSUMED;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
