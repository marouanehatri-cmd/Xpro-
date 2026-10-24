package com.xpro.lite;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * X Pro Lite: x.com wrapper that never streams video inside the page.
 *  - all video.twimg.com requests from the page are blocked (GIFs excepted)
 *  - injected.js covers each video with a thumbnail + "Play Low Data Video" button
 *  - the button opens VideoPlayerActivity (ExoPlayer) with the lowest-bitrate mp4
 */
public class MainActivity extends Activity {

    // ------------------------------------------------------------------ constants

    private static final String START_URL = "https://x.com/home";
    private static final String[] ALLOWED_DOMAINS = {"x.com", "twitter.com", "t.co", "twimg.com"};
    private static final long SPLASH_MS = 2000L;
    private static final int REQ_FILE_CHOOSER = 100;
    private static final int REQ_PERMISSIONS = 101;

    private static final int[] TAB_IDS = {
            R.id.nav_home, R.id.nav_search, R.id.nav_grok,
            R.id.nav_notifications, R.id.nav_messages};
    private static final String[] TAB_URLS = {
            "https://x.com/home",
            "https://x.com/explore",
            "https://x.com/i/grok",
            "https://x.com/notifications",
            "https://x.com/messages"};

    // ------------------------------------------------------------------ state

    private FrameLayout root;
    private View content;
    private View splash;
    private SwipeRefreshLayout swipe;
    private WebView webView;
    private final ImageView[] tabViews = new ImageView[TAB_IDS.length];

    private XChromeClient chromeClient;
    private ValueCallback<Uri[]> filePathCallback;

    private String injectedScript = "";
    private boolean docStartSupported;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        content = findViewById(R.id.content);
        splash = findViewById(R.id.splash);
        swipe = findViewById(R.id.swipe);
        webView = findViewById(R.id.webview);

        enterImmersiveMode();
        injectedScript = buildInjectedScript();
        setupWebView();
        setupPullToRefresh();
        setupBottomNav();
        showSplash();
        requestNeededPermissions();

        ContextCompat.registerReceiver(this, downloadDoneReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED);

        applyCacheMode();
        webView.loadUrl(START_URL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        applyCacheMode();
    }

    @Override
    protected void onPause() {
        webView.onPause();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersiveMode();
    }

    @Override
    public void onBackPressed() {
        if (chromeClient != null && chromeClient.isCustomViewShown()) {
            chromeClient.onHideCustomView();
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(downloadDoneReceiver); } catch (Exception ignored) { }
        ((ViewGroup) webView.getParent()).removeView(webView);
        webView.destroy();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI shell

    private void enterImmersiveMode() {
        WindowInsetsControllerCompat c =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(WindowInsetsCompat.Type.systemBars());
    }

    private void showSplash() {
        splash.postDelayed(() -> splash.animate()
                .alpha(0f)
                .setDuration(250)
                .withEndAction(() -> splash.setVisibility(View.GONE))
                .start(), SPLASH_MS);
    }

    private void setupPullToRefresh() {
        swipe.setColorSchemeColors(0xFF1D9BF0);
        swipe.setProgressBackgroundColorSchemeColor(0xFF16181C);
        swipe.setOnRefreshListener(() -> { applyCacheMode(); webView.reload(); });
        swipe.setOnChildScrollUpCallback((parent, child) -> webView.getScrollY() > 0);
    }

    private void setupBottomNav() {
        for (int i = 0; i < TAB_IDS.length; i++) {
            final int index = i;
            tabViews[i] = findViewById(TAB_IDS[i]);
            tabViews[i].setOnClickListener(v -> onTabClicked(index));
        }
        findViewById(R.id.nav_downloads).setOnClickListener(v -> openDownloads());
        selectTab(0);
    }

    private void onTabClicked(int index) {
        if (tabViews[index].isSelected()) {
            webView.evaluateJavascript("window.scrollTo({top:0,behavior:'smooth'})", null);
        } else {
            selectTab(index);
            applyCacheMode();
            webView.loadUrl(TAB_URLS[index]);
        }
    }

    private void selectTab(int index) {
        for (int i = 0; i < tabViews.length; i++) tabViews[i].setSelected(i == index);
    }

    private void highlightTabForUrl(String url) {
        String path = Uri.parse(url == null ? "" : url).getPath();
        if (path == null) path = "";
        int index = -1;
        if (path.startsWith("/home")) index = 0;
        else if (path.startsWith("/explore") || path.startsWith("/search")) index = 1;
        else if (path.startsWith("/i/grok")) index = 2;
        else if (path.startsWith("/notifications")) index = 3;
        else if (path.startsWith("/messages") || path.startsWith("/i/chat")) index = 4;
        selectTab(index);
    }

    private void openDownloads() {
        try {
            startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            toast("No Downloads app found");
        }
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setMediaPlaybackRequiresUserGesture(true);     // no autoplay

        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);

        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));

        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setBackgroundColor(Color.BLACK);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new Bridge(), "Android");

        chromeClient = new XChromeClient();
        webView.setWebChromeClient(chromeClient);
        webView.setWebViewClient(new XClient());
        webView.setOnLongClickListener(v -> onWebViewLongClick());

        docStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
        if (docStartSupported) {
            Set<String> origins = new HashSet<>(Arrays.asList(
                    "https://x.com", "https://*.x.com",
                    "https://twitter.com", "https://*.twitter.com"));
            WebViewCompat.addDocumentStartJavaScript(webView, injectedScript, origins);
        }
    }

    /** Offline: show cached pages when there is no connection, normal loading otherwise. */
    private void applyCacheMode() {
        webView.getSettings().setCacheMode(isOnline()
                ? WebSettings.LOAD_DEFAULT
                : WebSettings.LOAD_CACHE_ELSE_NETWORK);
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities nc = cm.getNetworkCapabilities(n);
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private String buildInjectedScript() {
        String css = readAsset("injected.css");
        String js = readAsset("injected.js");
        return "window.__XPRO_CSS=" + JSONObject.quote(css) + ";\n" + js;
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ domain lock

    private static boolean isAllowed(Uri u) {
        if (u == null || !"https".equals(u.getScheme())) return false;
        String host = u.getHost();
        if (host == null) return false;
        for (String d : ALLOWED_DOMAINS) {
            if (host.equals(d) || host.endsWith("." + d)) return true;
        }
        return false;
    }
      private class XClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (isAllowed(u)) return false;
            toast("Blocked: " + (u.getHost() != null ? u.getHost() : u.getScheme()));
            return true;
        }

        /**
         * Data saver: the page itself never downloads video. Everything on video.twimg.com
         * (HD, SD, audio, playlists, segments) is answered with an empty 403.
         * GIFs (/tweet_video/) are tiny and stay allowed. Thumbnails come from pbs.twimg.com
         * and are cached by the WebView as usual.
         */
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            String host = u.getHost();
            if (host != null && (host.equals("video.twimg.com") || host.endsWith(".video.twimg.com"))) {
                String path = u.getPath() == null ? "" : u.getPath();
                if (!path.startsWith("/tweet_video/")) {
                    return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked by X Pro Lite",
                            new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
                }
            }
            return super.shouldInterceptRequest(view, request);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            swipe.setRefreshing(false);
            highlightTabForUrl(url);
            if (!docStartSupported && isAllowed(Uri.parse(url))) {
                view.evaluateJavascript(injectedScript, null);
            }
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            highlightTabForUrl(url);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                swipe.setRefreshing(false);
                toast("Can't load. Check your connection.");
            }
        }
    }

    private class XChromeClient extends WebChromeClient {
        private View customView;
        private CustomViewCallback customViewCallback;

        boolean isCustomViewShown() { return customView != null; }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) { callback.onCustomViewHidden(); return; }
            customView = view;
            customViewCallback = callback;
            content.setVisibility(View.GONE);
            root.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        @Override
        public void onHideCustomView() {
            if (customView == null) return;
            root.removeView(customView);
            customView = null;
            content.setVisibility(View.VISIBLE);
            if (customViewCallback != null) customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }

        @Override
        public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (filePathCallback != null) filePathCallback.onReceiveValue(null);
            filePathCallback = callback;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER);
            } catch (Exception e) {
                filePathCallback = null;
                return false;
            }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER && filePathCallback != null) {
            filePathCallback.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ------------------------------------------------------------------ permissions

    private void requestNeededPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMISSIONS);
    }

    // ------------------------------------------------------------------ JS bridge

    /** Exposed to the page as window.Android. Every argument is validated. */
    private class Bridge {
        @JavascriptInterface
        public void playLowDataVideo(String lowUrl, String highUrl, String tweetId,
                                     double lowBytes, double highBytes) {
            runOnUiThread(() ->
                    openPlayer(lowUrl, highUrl, tweetId, (long) lowBytes, (long) highBytes));
        }

        @JavascriptInterface
        public void toast(String msg) {
            runOnUiThread(() -> MainActivity.this.toast(msg));
        }
    }

    private void openPlayer(String low, String high, String tweetId, long lowBytes, long highBytes) {
        if (!Downloads.isMp4Url(Uri.parse(low == null ? "" : low))) {
            toast("Rejected video URL");
            return;
        }
        if (!Downloads.isMp4Url(Uri.parse(high == null ? "" : high))) high = low;
        Intent i = new Intent(this, VideoPlayerActivity.class);
        i.putExtra(VideoPlayerActivity.EXTRA_LOW, low);
        i.putExtra(VideoPlayerActivity.EXTRA_HIGH, high);
        i.putExtra(VideoPlayerActivity.EXTRA_ID, tweetId);
        i.putExtra(VideoPlayerActivity.EXTRA_LOW_BYTES, lowBytes);
        i.putExtra(VideoPlayerActivity.EXTRA_HIGH_BYTES, highBytes);
        startActivity(i);
    }

    private void downloadImage(String hdUrl) {
        Uri uri = Uri.parse(hdUrl);
        if (!Downloads.isTwimgHttps(uri)) { toast("Rejected download URL"); return; }
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) name = "image";
        if (!name.contains(".")) {
            String fmt = uri.getQueryParameter("format");
            name += "." + (fmt != null ? fmt : "jpg");
        }
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        String err = Downloads.enqueue(this, hdUrl, name, mimeForName(name),
                webView.getSettings().getUserAgentString());
        toast(err == null ? "Downloading..." : err);
    }

    private static String mimeForName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }

    private final BroadcastReceiver downloadDoneReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (!Downloads.ACTIVE.remove(id)) return;
            boolean ok = false;
            DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
            try (Cursor cur = dm.query(new DownloadManager.Query().setFilterById(id))) {
                if (cur != null && cur.moveToFirst()) {
                    int col = cur.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    ok = col >= 0 && cur.getInt(col) == DownloadManager.STATUS_SUCCESSFUL;
                }
            }
            Toast.makeText(c, ok ? "Saved to Download/" + Downloads.SUBDIR : "Download failed",
                    Toast.LENGTH_LONG).show();
        }
    };

    // ------------------------------------------------------------------ long-press image menu

    private boolean onWebViewLongClick() {
        WebView.HitTestResult r = webView.getHitTestResult();
        int type = r.getType();
        if (type == WebView.HitTestResult.IMAGE_TYPE
                || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            String extra = r.getExtra();
            if (extra != null && Downloads.isTwimgHttps(Uri.parse(extra))) {
                showImageDialog(extra);
                return true;
            }
        }
        return false;
    }

    private void showImageDialog(String url) {
        final boolean profile = url.contains("/profile_images/");
        final String hd = toHdUrl(url);

        List<String> items = new ArrayList<>();
        items.add(profile ? "Download profile picture (HD)" : "Download HD Image");
        items.add("Copy image link");
        if (profile) items.add("Copy bio");

        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(profile ? "Profile picture" : "Image")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 0) downloadImage(hd);
                    else if (which == 1) { copyToClipboard("Image link", hd); toast("Link copied"); }
                    else if (which == 2) copyBio();
                })
                .show();
    }

    static String toHdUrl(String url) {
        try {
            Uri u = Uri.parse(url);
            String path = u.getPath() == null ? "" : u.getPath();
            if (path.contains("/profile_images/")) {
                return url.replaceAll(
                        "_(normal|bigger|mini|200x200|400x400|reasonably_small)(\\.[A-Za-z0-9]+)", "$2");
            }
            if (path.startsWith("/media/")) {
                Uri.Builder b = u.buildUpon().clearQuery();
                for (String k : u.getQueryParameterNames()) {
                    if (!k.equals("name")) b.appendQueryParameter(k, u.getQueryParameter(k));
                }
                b.appendQueryParameter("name", "orig");
                return b.build().toString();
            }
        } catch (Exception ignored) { }
        return url;
    }

    private void copyBio() {
        String js = "(function(){var e=document.querySelector('[data-testid=\"UserDescription\"]');"
                + "return e?e.innerText:'';})()";
        webView.evaluateJavascript(js, value -> {
            String bio = "";
            try {
                Object o = new JSONTokener(value).nextValue();
                if (o instanceof String) bio = (String) o;
            } catch (Exception ignored) { }
            if (bio.trim().isEmpty()) {
                toast("No bio found on this page");
            } else {
                copyToClipboard("Bio", bio);
                toast("Bio copied");
            }
        });
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(label, text));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
          }
