package cn.wzyp.view;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phone-side data update workflow. Supports a user-selected SQLite .db/.sqlite
 * and a custom HTTPS URL serving raw SQLite bytes. It never scrapes external sites.
 * Copy -> SQLite schema & integrity validation -> hot-swap with backup/rollback.
 */
public final class DataUpdateManager {
    public static final int PICK_DATABASE=702;
    public static final int EXPORT_DATABASE=703;
    private static final long MAX_BYTES=600L*1024L*1024L;
    private final Activity activity;
    private final OfflineStore store;
    private final PublicStorefrontUpdater storefront;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final AtomicBoolean working=new AtomicBoolean(false);
    private volatile WebView browser;

    public DataUpdateManager(Activity activity, OfflineStore store) {
        this.activity=activity;
        this.store=store;
        storefront=new PublicStorefrontUpdater(activity,store);
    }

    public void attachWebView(WebView w){browser=w;}

    private static JSONObject put(JSONObject obj,String key,Object value) {
        try {obj.put(key,value);}catch(JSONException ignored){}
        return obj;
    }

    private void notifyJs(String stage,String message,int percent,JSONObject details) {
        JSONObject payload=new JSONObject();
        put(payload,"stage",stage);
        put(payload,"message",message);
        put(payload,"percent",percent);
        if(details!=null)put(payload,"details",details);
        WebView w=browser;
        if(w!=null)w.post(()->{
            if(browser==w)w.evaluateJavascript(
                "window.__wzypDbUpdate("+payload.toString()+")",null);
        });
    }

    @JavascriptInterface
    public String publicShopStatus() {
        return storefront.info().toString();
    }

    @JavascriptInterface
    public String publicShopSettings() {
        android.content.SharedPreferences prefs=activity.getSharedPreferences("wzyp_public_sync_v6",0);
        JSONObject state=storefront.info();
        put(state,"auto",prefs.getBoolean("auto",false));
        return state.toString();
    }

    @JavascriptInterface
    public String publicShopSetHost(String selectedHost) {
        if(!"wzyp.cn".equals(selectedHost)&&!"pay.ldxp.cn".equals(selectedHost))
            return put(new JSONObject(),"_error","仅支持两个明确的链动小铺公开域名").toString();
        android.content.SharedPreferences settings=
            activity.getSharedPreferences("wzyp_public_sync_v6",0);
        boolean hostChanged=!selectedHost.equals(settings.getString("host","wzyp.cn"));
        android.content.SharedPreferences.Editor edit=settings.edit().putString("host",selectedHost);
        if(hostChanged)edit.remove("hold_until_ms").remove("error_kind");
        edit.apply();
        return publicShopSettings();
    }

    @JavascriptInterface
    public String publicShopSetAuto(boolean enabled) {
        android.content.SharedPreferences preferences=
            activity.getSharedPreferences("wzyp_public_sync_v6",0);
        boolean previous=preferences.getBoolean("auto",false);
        try {
            if(!preferences.edit().putBoolean("auto",enabled).commit())
                throw new Exception("自动同步开关无法写入手机设置");
            PublicRefreshJob.schedule(activity);
            return publicShopSettings();
        }catch(Exception e){
            // A rejected scheduler request must not leave the switch falsely enabled.
            preferences.edit().putBoolean("auto",previous).commit();
            String reason=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
            if(reason.contains("ACCESS_NETWORK_STATE"))
                reason="当前安装版本缺少系统网络状态权限，请安装修复版 APK";
            return put(new JSONObject(),"_error","自动更新设置未生效："+reason).toString();
        }
    }

    @JavascriptInterface
    public String publicShopSkip() {
        if(working.get())
            return put(new JSONObject(),"_error","请先停止当前同步，再跳过店铺").toString();
        try {
            return storefront.skipCurrentShop().toString();
        } catch(Exception e){
            return put(new JSONObject(),"_error","跳过店铺失败："+e.getMessage()).toString();
        }
    }

    /** Cancel a running public refresh immediately; queued tasks retain SQLite integrity. */
    @JavascriptInterface
    public void publicShopCancel() {
        if(working.get()){
            storefront.cancel();
            notifyJs("public_checking","已请求取消，正在停止网络请求…",-1,null);
        }
    }

    @JavascriptInterface
    public void publicShopRefresh(String target,int count) {
        if(!working.compareAndSet(false,true)){
            notifyJs("public_error","已有同步或数据库任务在运行",0,null);
            return;
        }
        io.execute(()->{
            try {
                storefront.prepareNextRun();
                PublicStorefrontUpdater.Progress progress=(stage,message,details)->
                    notifyJs("public_"+stage,message,-1,details);
                JSONObject result;
                String token=target==null?"":target.trim();
                if(token.isEmpty()){
                    result=storefront.refreshBatch(count,progress);
                }else{
                    // No arbitrary URLs/hosts accepted by the native API bridge.
                    if(token.startsWith("https://wzyp.cn/shop/"))
                        token=token.substring("https://wzyp.cn/shop/".length()).replaceAll("/$","");
                    else if(token.startsWith("https://pay.ldxp.cn/shop/"))
                        token=token.substring("https://pay.ldxp.cn/shop/".length()).replaceAll("/$","");
                    result=storefront.refreshOne(token,progress);
                }
                notifyJs("public_done","公开店铺商品更新完成",100,result);
            }catch(Exception e){
                notifyJs("public_error","公开店铺刷新失败："+e.getMessage(),0,null);
            }finally{working.set(false);}
        });
    }

    @JavascriptInterface
    public String deltaSettings() {
        return DeltaSyncEngine.settings(activity,store).toString();
    }

    @JavascriptInterface
    public String saveDeltaSettings(String raw) {
        try {
            return DeltaSyncEngine.save(activity,store,new JSONObject(raw)).toString();
        }catch(Exception e){
            return put(new JSONObject(),"_error",e.getMessage()).toString();
        }
    }

    @JavascriptInterface
    public void syncDelta() {
        if(!working.compareAndSet(false,true)) {
            notifyJs("error","另一个数据库更新任务正在执行",0,null);
            return;
        }
        io.execute(()->{
            try {
                DeltaSyncEngine.sync(activity,store,(stage,message,details)->
                    notifyJs(stage,message,"updated".equals(stage)?100:-1,details));
            }catch(Exception ignored){
                // DeltaSyncEngine already relayed the exact error to the UI.
            }finally {
                working.set(false);
            }
        });
    }

    @JavascriptInterface
    public void exportFile() {
        if(working.get()){
            notifyJs("export_error","请等待正在执行的更新任务结束",0,null);
            return;
        }
        activity.runOnUiThread(()->{
            Intent picker=new Intent(Intent.ACTION_CREATE_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            picker.setType("application/octet-stream");
            String filename="wzyp-backup-"+new java.text.SimpleDateFormat(
                "yyyyMMdd-HHmmss",Locale.ROOT).format(new java.util.Date())+".db";
            picker.putExtra(Intent.EXTRA_TITLE,filename);
            try{activity.startActivityForResult(picker,EXPORT_DATABASE);}
            catch(Exception e){notifyJs("export_error","无法打开文件保存器："+e.getMessage(),0,null);}
        });
    }

    public void onExportResult(int result,Intent intent) {
        if(result!=Activity.RESULT_OK || intent==null || intent.getData()==null)return;
        final Uri destination=intent.getData();
        if(!working.compareAndSet(false,true)){
            notifyJs("export_error","已有数据库更新任务正在进行，请稍后重试",0,null);
            return;
        }
        io.execute(()->{
            try(OutputStream out=activity.getContentResolver().openOutputStream(destination,"w")){
                if(out==null)throw new Exception("无法打开选定的保存位置");
                notifyJs("export_progress","校验 SQLite 并保存到所选位置…",-1,null);
                JSONObject info=store.exportSnapshot(out);
                notifyJs("export_done","数据库已导出（"+
                    String.format(Locale.ROOT,"%.1f",info.optLong("exported_bytes")/1048576.0)+
                    " MB），请妥善保管备份文件",100,info);
            }catch(Exception e){
                notifyJs("export_error","导出失败："+e.getMessage()+"；若文件已创建请删除不完整文件",0,null);
            }finally{
                working.set(false);
            }
        });
    }

    @JavascriptInterface
    public void chooseFile() {
        if(working.get()) {
            notifyJs("error","数据库更新正在进行",0,null);
            return;
        }
        activity.runOnUiThread(()->{
            Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            picker.setType("*/*");
            picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {activity.startActivityForResult(picker,PICK_DATABASE);}
            catch(Exception e){notifyJs("error","无法打开系统文件选择器："+e.getMessage(),0,null);}
        });
    }

    public void onFileResult(int result, Intent intent) {
        if(result!=Activity.RESULT_OK || intent==null || intent.getData()==null)return;
        final Uri uri=intent.getData();
        if(!working.compareAndSet(false,true)) {
            notifyJs("error","已有更新任务在执行",0,null);
            return;
        }
        io.execute(()->{
            try(InputStream in=activity.getContentResolver().openInputStream(uri)) {
                if(in==null)throw new Exception("无法读取文件内容");
                update(in,-1,"导入本地文件",false);
            }catch(Exception e){ notifyJs("error","导入失败："+e.getMessage(),0,null); }
            finally {working.set(false);}
        });
    }

    @JavascriptInterface
    public void download(String rawUrl) {
        if(!working.compareAndSet(false,true)){
            notifyJs("error","已有更新任务在执行",0,null);
            return;
        }
        io.execute(()->{
            HttpURLConnection conn=null;
            try{
                URL url=new URL(rawUrl==null?"":rawUrl.trim());
                // Explicit user URL, but a public/signed URL must never be fetched over cleartext.
                for(int hop=0;hop<=4;hop++){
                    if(!"https".equalsIgnoreCase(url.getProtocol())||
                        url.getUserInfo()!=null||url.getHost().isEmpty())
                        throw new Exception("数据库更新链接必须是 HTTPS URL");
                    conn=(HttpURLConnection)url.openConnection();
                    conn.setInstanceFollowRedirects(false);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(60000);
                    conn.setRequestProperty("Accept","application/vnd.sqlite3,application/octet-stream,*/*");
                    int status=conn.getResponseCode();
                    if(status==301||status==302||status==303||status==307||status==308){
                        String location=conn.getHeaderField("Location");
                        if(location==null)throw new Exception("服务器重定向缺少地址");
                        URL redirected=new URL(url,location);
                        conn.disconnect();
                        conn=null;
                        url=redirected;
                        if(hop==4)throw new Exception("重定向次数过多");
                        continue;
                    }
                    if(status!=200)throw new Exception("服务器 HTTP "+status+"，请检查更新 URL");
                    long length=conn.getContentLengthLong();
                    if(length>MAX_BYTES)throw new Exception("远程数据库超过 600 MB");
                    try(InputStream in=conn.getInputStream()){
                        update(in,length,"下载 HTTPS 数据库",true);
                    }
                    return;
                }
            }catch(Exception e){notifyJs("error","下载失败："+e.getMessage(),0,null);}
            finally {
                if(conn!=null)conn.disconnect();
                working.set(false);
            }
        });
    }

    @JavascriptInterface
    public void resetBundled() {
        if(!working.compareAndSet(false,true)){
            notifyJs("error","已有更新任务在执行",0,null);return;
        }
        io.execute(()->{
            try(InputStream in=activity.getAssets().open("seed.db")){
                update(in,-1,"恢复安装包内置快照",false);
            }catch(Exception e){notifyJs("error","恢复失败："+e.getMessage(),0,null);}
            finally {working.set(false);}
        });
    }

    private void update(InputStream in,long length,String source,boolean remote) throws Exception {
        File staging=new File(activity.getDatabasePath("wzyp_snapshot_v1.db").getAbsolutePath()+".next");
        if(staging.exists() && !staging.delete())throw new Exception("临时数据库清理失败");
        long current=0;
        try{
            notifyJs("copying",source+"：正在复制数据库",0,null);
            try(OutputStream out=new FileOutputStream(staging)) {
                byte[] bytes=new byte[64*1024];
                int n;
                int lastPercent=-1;
                while((n=in.read(bytes))!=-1){
                    current+=n;
                    if(current>MAX_BYTES)throw new Exception("文件超过 600 MB 限制");
                    out.write(bytes,0,n);
                    int progress=length>0?(int)Math.min(94,current*94/length):-1;
                    if(progress!=lastPercent && (progress<0 && (current%(4*1024*1024)<n)||progress>=0)){
                        lastPercent=progress;
                        notifyJs("copying",source+"：已处理 "+
                            String.format(Locale.ROOT,"%.1f",current/1048576.0)+" MB",progress,null);
                    }
                }
                out.flush();
                if(out instanceof FileOutputStream)((FileOutputStream)out).getFD().sync();
            }
            if(current<4096)throw new Exception("文件太小，必须选择完整 SQLite 数据库");
            notifyJs("validating","检查 SQLite 完整性及店铺/商品表结构…",96,null);
            JSONObject info=store.installSnapshot(staging,source+(remote?"（远程）":""));
            // The delta feed ETag is not valid after importing an unrelated full snapshot.
            DeltaSyncEngine.preferences(activity).edit().remove("etag").apply();
            notifyJs("done","更新成功："+info.optLong("shops")+" 家店铺、"+
                info.optLong("goods")+" 件商品、"+info.optLong("details")+" 条详情",100,info);
        }finally {
            if(staging.exists())staging.delete();
        }
    }

    public void close() {
        browser=null;
        io.shutdownNow();
    }
}
