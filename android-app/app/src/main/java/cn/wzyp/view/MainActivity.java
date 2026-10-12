package cn.wzyp.view;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final String PREFS = "wzyp_android";
    private static final String SERVER_KEY = "server_url";
    private static final int FILE_REQUEST = 501;
    private FrameLayout area;
    private LinearLayout setupPanel;
    private WebView browser;
    private ProgressBar progress;
    private TextView title;
    private EditText serverInput;
    private android.webkit.ValueCallback<Uri[]> uploadCallback;
    private String serverUrl = "";

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button action(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        return button;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        serverUrl = getPreferences(MODE_PRIVATE).getString(SERVER_KEY, "");
        buildLayout();
        if (serverUrl.isEmpty()) showSettings();
        else showBrowser();
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff5f7fb);
        setContentView(root);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(3), dp(7), dp(3));
        bar.setBackgroundColor(Color.WHITE);
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(54)));

        title = label("▥ 链动小铺洞察", 16, 0xff344de0);
        title.setTypeface(null, 1);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        Button refresh = action("刷新");
        refresh.setOnClickListener(v -> {
            if (browser != null && browser.getVisibility() == View.VISIBLE) browser.reload();
            else showSettings();
        });
        bar.addView(refresh, new LinearLayout.LayoutParams(dp(68), dp(48)));

        Button settings = action("服务器");
        settings.setOnClickListener(v -> showSettings());
        bar.addView(settings, new LinearLayout.LayoutParams(dp(78), dp(48)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        area = new FrameLayout(this);
        root.addView(area, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void showSettings() {
        progress.setVisibility(View.GONE);
        if (browser != null) browser.setVisibility(View.GONE);
        if (setupPanel == null) buildSetupPanel();
        serverInput.setText(serverUrl);
        setupPanel.setVisibility(View.VISIBLE);
        title.setText("▥ 配置服务器");
    }

    private void buildSetupPanel() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        area.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setupPanel = new LinearLayout(this);
        setupPanel.setOrientation(LinearLayout.VERTICAL);
        setupPanel.setPadding(dp(22), dp(36), dp(22), dp(24));
        scroll.addView(setupPanel, new ScrollView.LayoutParams(-1, -2));

        TextView heading = label("连接数据洞察服务器", 24, 0xff1f2937);
        heading.setTypeface(null, 1);
        setupPanel.addView(heading);

        TextView info = label("\nAndroid 客户端已就绪。请填写你部署的 wzyp-view 后端地址，才能使用店铺数据、商品查询及 AI 分析。\n", 15, 0xff606b7d);
        info.setLineSpacing(dp(5), 1);
        setupPanel.addView(info);

        setupPanel.addView(label("服务器地址（必须包含 http:// 或 https://）", 13, 0xff374151));
        serverInput = new EditText(this);
        serverInput.setSingleLine(true);
        serverInput.setTextSize(15);
        serverInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        serverInput.setHint("https://example.com  或  http://192.168.1.5:8770");
        setupPanel.addView(serverInput, new LinearLayout.LayoutParams(-1, dp(58)));

        Button connect = action("保存并连接");
        connect.setOnClickListener(v -> saveAndConnect());
        setupPanel.addView(connect, new LinearLayout.LayoutParams(-1, dp(56)));

        TextView note = label("\n• 手机和服务端在同一局域网时，可使用电脑的局域网 IP，如 http://192.168.1.5:8770。\n\n• 公网服务器必须使用 HTTPS，建议在反向代理层加入认证，不能直接暴露管理接口。\n\n• 127.0.0.1 指向手机本身，不是你的电脑。\n\n• APK 不包含 Python、MySQL 或大模型服务。", 13, 0xff6b7280);
        note.setLineSpacing(dp(4), 1);
        setupPanel.addView(note);
    }

    private static boolean isPrivateHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".local") ||
                host.equals("::1") || host.startsWith("fe80:") || host.startsWith("fc") ||
                host.startsWith("fd") || host.startsWith("127.") ||
                host.startsWith("10.") || host.startsWith("192.168.")) return true;
        String[] octets = host.split("\\.");
        if (octets.length == 4) {
            try {
                int first = Integer.parseInt(octets[0]);
                int second = Integer.parseInt(octets[1]);
                return first == 172 && second >= 16 && second <= 31;
            } catch (NumberFormatException ignored) { }
        }
        return false;
    }

    private void saveAndConnect() {
        String raw = serverInput.getText().toString().trim();
        while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        Uri uri = Uri.parse(raw);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        boolean secure = "https".equalsIgnoreCase(scheme);
        boolean local = "http".equalsIgnoreCase(scheme) && isPrivateHost(host);
        if (host == null || host.isEmpty() || !(secure || local) ||
                uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null ||
                (uri.getPath() != null && !uri.getPath().isEmpty())) {
            new AlertDialog.Builder(this).setTitle("地址格式不正确")
                    .setMessage("请输入后端根地址，例如 https://domain.example 或 http://192.168.1.5:8770。公网只允许 HTTPS。")
                    .setPositiveButton("确定", null).show();
            return;
        }
        serverUrl = raw;
        getPreferences(MODE_PRIVATE).edit().putString(SERVER_KEY, serverUrl).apply();
        showBrowser();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void showBrowser() {
        if (serverUrl.isEmpty()) { showSettings(); return; }
        if (setupPanel != null) setupPanel.setVisibility(View.GONE);
        title.setText("▥ 链动小铺洞察");
        if (browser == null) {
            browser = new WebView(this);
            area.addView(browser, new FrameLayout.LayoutParams(-1, -1));
            WebSettings s = browser.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setAllowFileAccess(false);
            s.setAllowContentAccess(true);
            s.setAllowFileAccessFromFileURLs(false);
            s.setAllowUniversalAccessFromFileURLs(false);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            s.setJavaScriptCanOpenWindowsAutomatically(false);
            s.setSupportMultipleWindows(false);
            s.setBuiltInZoomControls(false);
            s.setDisplayZoomControls(false);
            s.setDefaultTextEncodingName("UTF-8");
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false);

            browser.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    Uri next = request.getUrl();
                    Uri base = Uri.parse(serverUrl);
                    if (base.getScheme().equalsIgnoreCase(next.getScheme()) &&
                            base.getHost().equalsIgnoreCase(next.getHost()) &&
                            base.getPort() == next.getPort()) return false;
                    String scheme = next.getScheme();
                    if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                        try { startActivity(new Intent(Intent.ACTION_VIEW, next)); }
                        catch (ActivityNotFoundException ignored) { Toast.makeText(MainActivity.this, "未找到浏览器", Toast.LENGTH_SHORT).show(); }
                    }
                    return true;
                }
                @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if (request.isForMainFrame())
                        Toast.makeText(MainActivity.this, "连接失败，请检查后端是否启动及手机网络", Toast.LENGTH_LONG).show();
                }
                @Override public void onPageFinished(WebView view, String url) {
                    progress.setVisibility(View.GONE);
                }
            });

            browser.setWebChromeClient(new WebChromeClient() {
                @Override public void onProgressChanged(WebView view, int value) {
                    progress.setVisibility(value == 100 ? View.GONE : View.VISIBLE);
                    progress.setProgress(value);
                }
                @Override public boolean onShowFileChooser(WebView webView,
                        android.webkit.ValueCallback<Uri[]> callback, FileChooserParams params) {
                    if (uploadCallback != null) uploadCallback.onReceiveValue(null);
                    uploadCallback = callback;
                    try {
                        Intent chooser = params.createIntent();
                        startActivityForResult(chooser, FILE_REQUEST);
                    } catch (Exception e) {
                        uploadCallback.onReceiveValue(null);
                        uploadCallback = null;
                        Toast.makeText(MainActivity.this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
            });

            browser.setDownloadListener((url, userAgent, disposition, mime, length) -> {
                if (url.startsWith("blob:")) {
                    Toast.makeText(this, "该网页内生成的文件，请改在浏览器导出", Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    Uri resource = Uri.parse(url);
                    String scheme = resource.getScheme();
                    if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return;
                    DownloadManager.Request download = new DownloadManager.Request(resource);
                    download.setMimeType(mime);
                    download.addRequestHeader("User-Agent", userAgent);
                    String cookies = CookieManager.getInstance().getCookie(url);
                    if (cookies != null) download.addRequestHeader("Cookie", cookies);
                    download.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(download);
                    Toast.makeText(this, "已添加下载任务", Toast.LENGTH_SHORT).show();
                } catch (Exception e) { Toast.makeText(this, "下载失败，请使用系统浏览器", Toast.LENGTH_LONG).show(); }
            });
        }
        browser.setVisibility(View.VISIBLE);
        String current = browser.getUrl();
        if (current == null || !current.startsWith(serverUrl + "/")) browser.loadUrl(serverUrl + "/");
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == FILE_REQUEST && uploadCallback != null) {
            uploadCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            uploadCallback = null;
        }
    }

    @Override public void onBackPressed() {
        if (setupPanel != null && setupPanel.getVisibility() == View.VISIBLE && !serverUrl.isEmpty()) {
            showBrowser();
        } else if (browser != null && browser.canGoBack()) {
            browser.goBack();
        } else {
            new AlertDialog.Builder(this).setMessage("退出链动小铺洞察？")
                    .setNegativeButton("取消", null).setPositiveButton("退出", (d, w) -> finish()).show();
        }
    }

    @Override protected void onDestroy() {
        if (uploadCallback != null) { uploadCallback.onReceiveValue(null); uploadCallback = null; }
        if (browser != null) { browser.stopLoading(); browser.destroy(); browser = null; }
        super.onDestroy();
    }
}
