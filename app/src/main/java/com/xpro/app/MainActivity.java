package com.xpro.app;

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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * X Pro: a single-activity wrapper around x.com that hides every "browser" cue.
 *
 *  - Immersive full-screen WebView + pull-to-refresh
 *  - Native bottom navigation bar (replaces X's own web navigation)
 *  - Domain lock (x.com, twitter.com, t.co, twimg.com)
 *  - injected.css / injected.js: UI cleanup, ad hiding, one-click video download
 *  - Long-press image menu (HD download, copy link, copy bio)
 *  - DownloadManager downloads into Download/XPro/
 */
public class MainActivity extends Activity {

    // ------------------------------------------------------------------ constants

    private static final String START_URL = "https://x.com/home";
    private static final String[] ALLOWED_DOMAINS = {"x.com", "twitter.com", "t.co", "twimg.com"};
    private static final String DOWNLOAD_SUBDIR = "XPro";
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

    private final Set<Long> activeDownloads = ConcurrentHashMap.newKeySet();

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

        webView.loadUrl(START_URL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
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
        swipe.setOnRefreshListener(() -> webView.reload());
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
        s.setMediaPlaybackRequiresUserGesture(false);

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

    private static boolean isTwimgHttps(Uri u) {
        if (u == null || !"https".equals(u.getScheme())) return false;
        String host = u.getHost();
        return host != null && (host.equals("twimg.com") || host.endsWith(".twimg.com"));
    }

    private class XClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (isAllowed(u)) return false;
            toast("Blocked: " + (u.getHost() != null ? u.getHost() : u.getScheme()));
            return true;
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

    private boolean needsLegacyStoragePermission() {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED;
    }

    private void requestNeededPermissions() {
        List<String> need = new ArrayList<>();
        if (needsLegacyStoragePermission()) {
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

    private class Bridge {
        @JavascriptInterface
        public void downloadVideo(String url, String tweetId) {
            runOnUiThread(() -> startVideoDownload(url, tweetId));
        }

        @JavascriptInterface
        public void toast(String msg) {
            runOnUiThread(() -> MainActivity.this.toast(msg));
        }
    }

    private void startVideoDownload(String url, String tweetId) {
        Uri uri = Uri.parse(url == null ? "" : url);
        String path = uri.getPath();
        if (!isTwimgHttps(uri) || path == null || !path.endsWith(".mp4")) {
            toast("Rejected download URL");
            return;
        }
        String id = tweetId == null ? "" : tweetId.replaceAll("[^0-9A-Za-z_-]", "");
        if (id.isEmpty()) id = "video_" + System.currentTimeMillis();
        enqueueDownload(url, id + ".mp4", "video/mp4");
    }

    private void downloadImage(String hdUrl) {
        Uri uri = Uri.parse(hdUrl);
        if (!isTwimgHttps(uri)) { toast("Rejected download URL"); return; }
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) name = "image";
        if (!name.contains(".")) {
            String fmt = uri.getQueryParameter("format");
            name += "." + (fmt != null ? fmt : "jpg");
        }
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        enqueueDownload(hdUrl, name, mimeForName(name));
    }

    private static String mimeForName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }

    @SuppressWarnings("deprecation")
    private void enqueueDownload(String url, String fileName, String mime) {
        try {
            if (needsLegacyStoragePermission()) {
                requestNeededPermissions();
                toast("Storage permission needed");
                return;
            }
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle(fileName);
            req.setDescription("X Pro");
            req.setMimeType(mime);
            req.addRequestHeader("User-Agent", webView.getSettings().getUserAgentString());
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, DOWNLOAD_SUBDIR + "/" + fileName);
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            activeDownloads.add(dm.enqueue(req));
            toast("Downloading...");
        } catch (Exception e) {
            toast("Download failed: " + e.getMessage());
        }
    }

    private final BroadcastReceiver downloadDoneReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (!activeDownloads.remove(id)) return;
            boolean ok = false;
            DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
            try (Cursor cur = dm.query(new DownloadManager.Query().setFilterById(id))) {
                if (cur != null && cur.moveToFirst()) {
                    int col = cur.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    ok = col >= 0 && cur.getInt(col) == DownloadManager.STATUS_SUCCESSFUL;
                }
            }
            Toast.makeText(c, ok ? "Saved to Download/XPro" : "Download failed",
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
            if (extra != null && isTwimgHttps(Uri.parse(extra))) {
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
        items.add(profile ? "Download pro
