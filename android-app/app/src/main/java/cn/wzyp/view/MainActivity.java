package cn.wzyp.view;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Local-only Android entry point. All assets and SQLite rows are inside the APK;
 * no Internet permission, endpoint selection, Web server or remote LLM.
 */
public final class MainActivity extends Activity {
    private static final int FILE_REQUEST=501;
    private WebView web;
    private android.webkit.ValueCallback<Uri[]> fileCallback;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xfff5f7fb);
        getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        try {
            OfflineStore store=new OfflineStore(this);
            web=new WebView(this);
            setContentView(web);
            WebSettings ws=web.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setAllowFileAccess(true); // only app-packaged file:///android_asset/ is loaded
            ws.setAllowContentAccess(true); // Android document picker
            ws.setAllowFileAccessFromFileURLs(false);
            ws.setAllowUniversalAccessFromFileURLs(false);
            ws.setBlockNetworkLoads(true);
            ws.setJavaScriptCanOpenWindowsAutomatically(false);
            ws.setSupportMultipleWindows(false);
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            ws.setDefaultTextEncodingName("UTF-8");
            web.addJavascriptInterface(store,"WzypOffline");
            web.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                    Uri link=request.getUrl();
                    if("file".equalsIgnoreCase(link.getScheme()) &&
                         "android_asset".equalsIgnoreCase(link.getHost()))return false;
                    String scheme=link.getScheme();
                    if(!"https".equalsIgnoreCase(scheme)&&!"http".equalsIgnoreCase(scheme))return true;
                    // External citations open outside the app. Network never loads in WebView.
                    try { startActivity(new Intent(Intent.ACTION_VIEW,link)); }
                    catch (ActivityNotFoundException e) {
                        Toast.makeText(MainActivity.this,"没有可用浏览器",Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
            });
            web.setWebChromeClient(new WebChromeClient() {
                @Override public boolean onShowFileChooser(WebView view,
                           android.webkit.ValueCallback<Uri[]> callback,FileChooserParams params) {
                    if(fileCallback!=null)fileCallback.onReceiveValue(null);
                    fileCallback=callback;
                    try{startActivityForResult(params.createIntent(),FILE_REQUEST);}
                    catch(Exception e){
                        fileCallback.onReceiveValue(null);
                        fileCallback=null;
                        Toast.makeText(MainActivity.this,"无法打开本地文件",Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
            });
            web.loadUrl("file:///android_asset/index.html");
        } catch(Exception e) {
            TextView message=new TextView(this);
            message.setText("离线数据初始化失败。请重新安装完整 APK。\n\n"+e.getMessage());
            message.setTextColor(Color.RED);
            message.setPadding(35,55,35,35);
            setContentView(message);
        }
    }

    @Override protected void onActivityResult(int req,int result,Intent data) {
        super.onActivityResult(req,result,data);
        if(req==FILE_REQUEST && fileCallback!=null){
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result,data));
            fileCallback=null;
        }
    }

    @Override public void onBackPressed() {
        if(web!=null && web.canGoBack()){web.goBack();return;}
        new AlertDialog.Builder(this).setTitle("链动小铺离线版")
            .setMessage("退出应用？")
            .setNegativeButton("取消",null)
            .setPositiveButton("退出",(d,w)->finish())
            .show();
    }

    @Override protected void onDestroy() {
        if(fileCallback!=null){fileCallback.onReceiveValue(null);fileCallback=null;}
        if(web!=null){web.stopLoading();web.destroy();web=null;}
        super.onDestroy();
    }
}
