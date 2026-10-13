package cn.wzyp.view;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import android.os.SystemClock;

/**
 * Direct read-only public storefront refresh for shops already present in offline SQLite.
 * Uses the documented public product-list request shape; no logins, purchases,
 * WAF bypass, private merchant API, CAPTCHA handling, or aggressive crawling.
 */
public final class PublicStorefrontUpdater {
    private static final String HOST="wzyp.cn";
    private static final String[] TYPES={"card","equity","resource","article"};
    private static final int MAX_PAGES_PER_TYPE=6;
    private static final int PAGE_SIZE=50;
    private static final int MAX_BODY=2*1024*1024;
    // Shared conservative pacing across foreground and background requests.
    // Two requests may be in flight, but starts are separated by at least 1 second.
    private static final long REQUEST_GAP_MS=1000;
    private static final Object PACE_LOCK=new Object();
    private static long nextStart=0;
    private static final String PREFS="wzyp_public_sync_v6";
    private final Context context;
    private final OfflineStore store;
    private volatile boolean cancelled=false;
    private final Set<HttpURLConnection> activeConnections=
        Collections.newSetFromMap(new ConcurrentHashMap<HttpURLConnection,Boolean>());

    /** Stop a foreground/background update safely before any more SQLite writes. */
    public void cancel(){
        cancelled=true;
        for(HttpURLConnection connection:activeConnections)connection.disconnect();
    }

    /** Reset only when beginning a completely new foreground task. */
    public void prepareNextRun(){cancelled=false;}

    private void requireActive() throws InterruptedException {
        if(cancelled || Thread.currentThread().isInterrupted())
            throw new InterruptedException("同步已取消，未完成的店铺不会写入数据库");
    }

    public interface Progress { void update(String stage,String message,JSONObject info); }

    PublicStorefrontUpdater(Context ctx,OfflineStore store){
        context=ctx.getApplicationContext();
        this.store=store;
    }

    private static JSONObject put(JSONObject o,String k,Object v){
        try{o.put(k,v);}catch(Exception ignored){}
        return o;
    }
    private SharedPreferences prefs(){return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    private String publicHost() {
        String host=prefs().getString("host",HOST);
        if("wzyp.cn".equals(host)||"pay.ldxp.cn".equals(host))return host;
        return HOST;
    }


    public JSONObject info(){
        JSONObject result=new JSONObject();
        SharedPreferences p=prefs();
        put(result,"host",publicHost());
        put(result,"offset",p.getInt("offset",0));
        put(result,"last_run",p.getLong("last_run",0));
        put(result,"last_success",p.getLong("last_success",0));
        put(result,"last_result",p.getString("last_result","尚未进行公开店铺刷新"));
        return result;
    }

    private static void waitPaced() throws InterruptedException {
        long delay;
        synchronized(PACE_LOCK){
            long now=SystemClock.elapsedRealtime();
            delay=Math.max(0,nextStart-now);
            nextStart=Math.max(now,nextStart)+REQUEST_GAP_MS;
        }
        if(delay>0)Thread.sleep(delay);
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("操作取消");
    }

    private JSONObject request(String token,String type,int page) throws Exception {
        requireActive();
        String host=publicHost();
        URL url=new URL("https://"+host+"/shopApi/Shop/goodsList");
        JSONObject payload=new JSONObject();
        put(payload,"token",token);
        put(payload,"keywords","");
        put(payload,"category_id","");
        put(payload,"goods_type",type);
        put(payload,"current",page);
        put(payload,"pageSize",PAGE_SIZE);
        byte[] data=payload.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection=(HttpURLConnection)url.openConnection();
        activeConnections.add(connection);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(18000);
        connection.setUseCaches(false);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
        connection.setRequestProperty("Accept","application/json, text/plain, */*");
        connection.setRequestProperty("Referer","https://"+host+"/shop/"+token);
        connection.setRequestProperty("Origin","https://"+host);
        connection.setRequestProperty("User-Agent","WzypView-Android/7.0");
        try {
            try(OutputStream output=connection.getOutputStream()){output.write(data);}
            int status=connection.getResponseCode();
            if(status==401||status==403||status==429)
                throw new Exception("平台拒绝访问或要求验证（HTTP "+status+"），已停止刷新；不会尝试绕过验证");
            if(status!=200)throw new Exception("平台接口 HTTP "+status);
            String contentType=connection.getContentType();
            if(contentType!=null && contentType.toLowerCase().contains("text/html"))
                throw new Exception("平台返回 HTML/验证页而非商品 JSON，已停止刷新");
            if(connection.getContentLengthLong()>MAX_BODY)
                throw new Exception("商品列表响应过大");
            ByteArrayOutputStream buf=new ByteArrayOutputStream();
            try(InputStream stream=connection.getInputStream()){
                byte[] bytes=new byte[8192];int count;
                while((count=stream.read(bytes))!=-1){
                    if(buf.size()+count>MAX_BODY)throw new Exception("响应超过 2 MB");
                    buf.write(bytes,0,count);
                }
            }
            JSONObject response=new JSONObject(new String(buf.toByteArray(),StandardCharsets.UTF_8));
            if(response.optInt("code",-1)!=1)
                throw new Exception("平台接口错误："+response.optString("msg","状态异常").substring(
                    0,Math.min(100,response.optString("msg","状态异常").length())));
            return response;
        }finally{
            activeConnections.remove(connection);
            connection.disconnect();
        }
    }

    private static String goodsKey(JSONObject item) {
        for(String field:new String[]{"goods_key","key","goodsKey","item_key"}){
            String value=item.optString(field,"").trim();
            if(value.matches("[A-Za-z0-9_-]{3,80}"))return value;
        }
        return "";
    }
    private static JSONArray extractRows(JSONObject body) throws Exception {
        Object data=body.opt("data");
        if(data instanceof JSONArray)return (JSONArray)data;
        if(!(data instanceof JSONObject))throw new Exception("店铺 API 未返回可识别的列表");
        JSONObject container=(JSONObject)data;
        for(String key:new String[]{"list","items","rows","records","goods","data"}){
            JSONArray rows=container.optJSONArray(key);
            if(rows!=null)return rows;
        }
        throw new Exception("无法识别平台商品列表字段");
    }

    private static int knownTotal(JSONObject body) {
        JSONObject data=body.optJSONObject("data");
        if(data==null)return -1;
        for(String key:new String[]{"total","count","total_count"}) {
            int total=data.optInt(key,-1);
            if(total>=0)return total;
        }
        return -1;
    }

    private static JSONObject normalized(JSONObject item,String key,String type) throws Exception {
        JSONObject out=new JSONObject();
        String name=item.optString("name",item.optString("title","")).trim();
        if(name.isEmpty())throw new Exception("平台商品缺少名称");
        Object priceValue=item.has("real_price") && !item.isNull("real_price")
            ?item.opt("real_price"):item.opt("price");
        double price;
        try{price=Double.parseDouble(String.valueOf(priceValue));}
        catch(Exception e){throw new Exception("平台商品价格不是数字");}
        if(!Double.isFinite(price)||price<0)throw new Exception("商品价格异常");
        String actualType=item.optString("goods_type",type);
        if(!Arrays.asList(TYPES).contains(actualType))actualType=type;
        JSONObject seller=item.optJSONObject("user");
        String shopName=seller==null?"":seller.optString("nickname","").trim();
        put(out,"shop_name",shopName);
        put(out,"goods_key",key);
        put(out,"goods_type",actualType);
        put(out,"name",name);
        put(out,"price",price);
        put(out,"market_price",item.opt("market_price"));
        Object status=item.opt("status");
        put(out,"status",status==null||status==JSONObject.NULL?1:status);
        return out;
    }

    private static final class CategoryResult {
        final HashMap<String,JSONObject> goods=new HashMap<>();
        int calls;
    }

    /** Each worker handles pages of one product type. Shared pacing avoids bursts. */
    private CategoryResult fetchCategory(String token,String type,Progress progress) throws Exception {
        CategoryResult result=new CategoryResult();
        for(int page=1;page<=MAX_PAGES_PER_TYPE;page++){
            requireActive();
            waitPaced();
            requireActive();
            if(progress!=null)progress.update("checking",
                "读取 "+token+" / "+type+" / 第 "+page+" 页",null);
            JSONObject answer=request(token,type,page);
            requireActive();
            result.calls++;
            JSONArray rows=extractRows(answer);
            for(int i=0;i<rows.length();i++){
                JSONObject item=rows.optJSONObject(i);
                if(item==null)throw new Exception("列表存在无效商品记录");
                String key=goodsKey(item);
                if(key.isEmpty())throw new Exception("无法识别商品 Key，拒绝更新");
                result.goods.put(key,normalized(item,key,type));
            }
            int total=knownTotal(answer);
            if(rows.length()==0 || rows.length()<PAGE_SIZE ||
                (total>=0 && page*PAGE_SIZE>=total))break;
            if(page==MAX_PAGES_PER_TYPE)
                throw new Exception("店铺 "+token+" 的 "+type+" 超出 "+MAX_PAGES_PER_TYPE+
                    " 页，为避免残缺数据已停止写入");
        }
        return result;
    }

    /**
     * Two concurrent category fetchers hide network latency. Actual HTTP starts are
     * globally paced at 1s. All four categories must complete before one DB merge.
     */
    public JSONObject refreshShop(String token,Progress progress) throws Exception {
        if(token==null||!token.matches("[A-Za-z0-9_-]{3,80}"))
            throw new Exception("店铺 Token 格式不正确");
        requireActive();
        long started=SystemClock.elapsedRealtime();
        ExecutorService pool=Executors.newFixedThreadPool(2);
        List<Future<CategoryResult>> results=new ArrayList<>();
        try {
            for(String type:TYPES)
                results.add(pool.submit(()->fetchCategory(token,type,progress)));
            HashMap<String,JSONObject> combined=new HashMap<>();
            int calls=0;
            for(Future<CategoryResult> future:results){
                CategoryResult part;
                try { part=future.get(); }
                catch(ExecutionException failure){
                    Throwable cause=failure.getCause();
                    throw new Exception(cause==null?"同步分类失败":cause.getMessage(),cause);
                }
                combined.putAll(part.goods);
                calls+=part.calls;
            }
            requireActive();
            JSONArray snapshot=new JSONArray();
            for(JSONObject good:combined.values())snapshot.put(good);
            requireActive();
            JSONObject summary=store.mergePublicShop(token,snapshot);
            put(summary,"requests",calls);
            put(summary,"elapsed_ms",SystemClock.elapsedRealtime()-started);
            if(progress!=null)progress.update("shop_done",
                token+"：新增 "+summary.optInt("added")+"，修改 "+summary.optInt("modified")+
                "，未变化 "+summary.optInt("unchanged")+"；耗时 "+
                String.format(java.util.Locale.ROOT,"%.1f",summary.optLong("elapsed_ms")/1000.0)+" 秒",
                summary);
            return summary;
        }finally {
            for(Future<CategoryResult> item:results)if(!item.isDone())item.cancel(true);
            pool.shutdownNow();
        }
    }

    /** Resume at last successful shop; stop immediately on errors / access challenges. */
    public JSONObject refreshBatch(int shopLimit,Progress progress) throws Exception {
        requireActive();
        int limit=Math.min(8,Math.max(1,shopLimit));
        SharedPreferences settings=prefs();
        int offset=settings.getInt("offset",0);
        JSONArray tokens=store.publicShopTokens(offset,limit);
        if(tokens.length()==0){
            offset=0;
            tokens=store.publicShopTokens(0,limit);
        }
        if(tokens.length()==0)throw new Exception("本地数据库没有店铺 Token");
        long started=SystemClock.elapsedRealtime();
        int changed=0,added=0,completed=0,requestCount=0;
        for(int index=0;index<tokens.length();index++){
            requireActive();
            String token=tokens.getString(index);
            try{
                JSONObject result=refreshShop(token,progress);
                added+=result.optInt("added");
                changed+=result.optInt("modified");
                requestCount+=result.optInt("requests");
                completed++;
                settings.edit().putInt("offset",offset+index+1)
                    .putLong("last_success",System.currentTimeMillis())
                    .putString("last_result","最近店铺 "+token+" 更新成功").apply();
            }catch(Exception e){
                settings.edit().putLong("last_run",System.currentTimeMillis())
                    .putString("last_result","店铺 "+token+" 更新失败："+e.getMessage()).apply();
                throw new Exception("已成功刷新 "+completed+" 家；在 "+token+
                    " 停止："+e.getMessage(),e);
            }
        }
        settings.edit().putLong("last_run",System.currentTimeMillis()).apply();
        JSONObject summary=new JSONObject();
        put(summary,"shops",completed);
        put(summary,"added",added);
        put(summary,"modified",changed);
        put(summary,"next_offset",offset+completed);
        put(summary,"requests",requestCount);
        put(summary,"elapsed_ms",SystemClock.elapsedRealtime()-started);
        return summary;
    }

    public JSONObject refreshOne(String token,Progress progress) throws Exception {
        requireActive();
        JSONObject result=refreshShop(token,progress);
        prefs().edit().putLong("last_run",System.currentTimeMillis())
            .putLong("last_success",System.currentTimeMillis())
            .putString("last_result","指定店铺 "+token+" 更新成功").apply();
        return result;
    }
}
