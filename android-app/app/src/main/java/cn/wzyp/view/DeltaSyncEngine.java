package cn.wzyp.view;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * User-owned versioned JSON change feed over HTTPS. No built-in platform scraper.
 * Network checks are conditional; SQLite changes are applied within one transaction.
 */
public final class DeltaSyncEngine {
    public static final String PREFS="wzyp_sync_v5";
    public static final int JOB_ID=301005;
    private static final int MAX_BYTES=35*1024*1024;
    private static final long PERIOD_MS=12L*60L*60L*1000L;

    private DeltaSyncEngine(){}
    public interface Progress {void onStatus(String status,String message,JSONObject details);}

    private static JSONObject put(JSONObject o,String k,Object value) {
        try{o.put(k,value);}catch(JSONException ignored){}
        return o;
    }

    public static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }

    public static JSONObject settings(Context context,OfflineStore store) {
        SharedPreferences prefs=preferences(context);
        JSONObject output=new JSONObject();
        put(output,"url",prefs.getString("url",""));
        put(output,"enabled",prefs.getBoolean("enabled",false));
        put(output,"revision",store.syncRevision());
        put(output,"last_check",prefs.getLong("last_check",0));
        put(output,"last_update",prefs.getLong("last_update",0));
        put(output,"last_result",prefs.getString("last_result","尚未同步"));
        return output;
    }

    private static URL validate(String input) throws Exception {
        if(input==null||input.length()>1800)throw new Exception("增量同步地址过长");
        URL url=new URL(input.trim());
        if(!"https".equalsIgnoreCase(url.getProtocol())||TextUtils.isEmpty(url.getHost())
                ||url.getUserInfo()!=null||url.getRef()!=null)
            throw new Exception("增量同步地址必须是 HTTPS，不能包含账号密码或锚点");
        return url;
    }

    public static synchronized JSONObject save(Context context,OfflineStore store,JSONObject input) throws Exception {
        SharedPreferences prefs=preferences(context);
        String url=input.optString("url","").trim();
        boolean enabled=input.optBoolean("enabled",false);
        if(!url.isEmpty())validate(url);
        if(enabled && url.isEmpty())throw new Exception("启用定期更新前，请先填写 HTTPS 增量接口");
        String previousUrl=prefs.getString("url","");
        boolean previousEnabled=prefs.getBoolean("enabled",false);
        String previousEtag=prefs.getString("etag","");
        SharedPreferences.Editor edit=prefs.edit();
        if(!url.equals(previousUrl))edit.remove("etag");
        edit.putString("url",url).putBoolean("enabled",enabled);
        if(!edit.commit())throw new Exception("保存同步设置失败");
        try {
            schedule(context);
        }catch(Exception failure){
            SharedPreferences.Editor restore=prefs.edit()
                .putString("url",previousUrl).putBoolean("enabled",previousEnabled);
            if(previousEtag.isEmpty())restore.remove("etag");
            else restore.putString("etag",previousEtag);
            restore.commit();
            throw new Exception("系统拒绝后台增量任务，原设置已保留："+failure.getMessage(),failure);
        }
        return settings(context,store);
    }

    public static void schedule(Context context) {
        JobScheduler jobs=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(jobs==null)return;
        SharedPreferences prefs=preferences(context);
        String url=prefs.getString("url","");
        if(!prefs.getBoolean("enabled",false)||url.isEmpty()){
            jobs.cancel(JOB_ID);return;
        }
        // Avoid resetting a 12-hour deadline every time the user opens the app.
        if(jobs.getPendingJob(JOB_ID)!=null)return;
        JobInfo info=new JobInfo.Builder(JOB_ID,new ComponentName(context,DeltaSyncJob.class))
            .setPeriodic(PERIOD_MS)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build();
        if(jobs.schedule(info)!=JobScheduler.RESULT_SUCCESS)
            throw new IllegalStateException("Android 未接受增量同步定时任务");
    }

    public static JSONObject sync(Context context,OfflineStore store,Progress cb) throws Exception {
        SharedPreferences prefs=preferences(context);
        String endpoint=prefs.getString("url","");
        if(endpoint.isEmpty())throw new Exception("未配置增量接口地址");
        URL url=validate(endpoint);
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("后台任务已停止");
        if(cb!=null)cb.onStatus("checking","正在检查增量数据…",null);
        HttpURLConnection conn=(HttpURLConnection)url.openConnection();
        conn.setInstanceFollowRedirects(false); // No leaking signed query URLs to a different host.
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setUseCaches(false);
        conn.setRequestProperty("Accept","application/json");
        String etag=prefs.getString("etag","");
        if(!etag.isEmpty())conn.setRequestProperty("If-None-Match",etag);
        String newEtag="";
        JSONObject result;
        try {
            int status=conn.getResponseCode();
            long checked=System.currentTimeMillis();
            if(status==304) {
                result=put(put(new JSONObject(),"status","unchanged"),"revision",store.syncRevision());
                prefs.edit().putLong("last_check",checked)
                    .putString("last_result","未发现新数据（304）").apply();
                if(cb!=null)cb.onStatus("unchanged","数据库已是最新版本（HTTP 304）",result);
                return result;
            }
            if(status!=200)throw new Exception("增量接口 HTTP "+status+"（应返回 200 JSON）");
            if(conn.getContentLengthLong()>MAX_BYTES)
                throw new Exception("增量包超过 35 MB，建议提供新完整数据库快照");
            newEtag=conn.getHeaderField("ETag");
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            try(InputStream input=conn.getInputStream()) {
                byte[] buf=new byte[16384];int count;
                while((count=input.read(buf))!=-1) {
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException("后台同步中止");
                    if(output.size()+count>MAX_BYTES)throw new Exception("同步包超过 35 MB");
                    output.write(buf,0,count);
                }
            }
            String json=new String(output.toByteArray(),StandardCharsets.UTF_8);
            JSONObject feed=new JSONObject(json);
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("后台同步中止，不写入数据库");
            if(cb!=null)cb.onStatus("applying","正在事务性更新手机 SQLite…",null);
            result=store.applyDelta(feed);
            String state=result.optString("status");
            SharedPreferences.Editor edit=prefs.edit()
                .putLong("last_check",checked)
                .putString("last_result","unchanged".equals(state)?"数据未变化":
                    "增量更新 "+result.optInt("upserted")+" 条、删除 "+result.optInt("deleted")+" 条");
            if("updated".equals(state))edit.putLong("last_update",System.currentTimeMillis());
            if(newEtag!=null && newEtag.length()<1000 && !newEtag.isEmpty())edit.putString("etag",newEtag);
            edit.apply();
            if(cb!=null)cb.onStatus(state,"updated".equals(state)?
                    "更新完成：新增/修改 "+result.optInt("upserted")+
                      " 条，删除 "+result.optInt("deleted")+" 条":
                    "数据版本无变化，不需要更新",result);
            return result;
        }catch(Exception error) {
            prefs.edit().putLong("last_check",System.currentTimeMillis())
                .putString("last_result","失败："+error.getMessage()).apply();
            if(cb!=null)cb.onStatus("error","增量同步失败："+error.getMessage(),null);
            throw error;
        }finally{conn.disconnect();}
    }
}
