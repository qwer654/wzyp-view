package cn.wzyp.view;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.Html;
import android.text.TextUtils;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bundled SQLite on-device backend. No sockets, remote services or remote LLM involved. */
public final class OfflineStore {
    // One SQLite owner per app process. Background jobs and WebView must share the
    // same synchronized instance so a background merge invalidates visible statistics.
    private static OfflineStore sharedInstance;

    public static synchronized OfflineStore getInstance(Context context) throws Exception {
        if(sharedInstance==null)sharedInstance=new OfflineStore(context.getApplicationContext());
        return sharedInstance;
    }

    private final Context context;
    private SQLiteDatabase db;
    private final File dbPath;
    private final SharedPreferences prefs;
    private JSONObject statsCache;
    private String statsRevision;

    private static final Set<String> SHOP_FILTERS = new HashSet<>(Arrays.asList(
        "token","nickname","login_province","contact_qq","sell_count",
        "deposit_money","goods_count","card_count","article_count",
        "resource_count","equity_count","auth_status","shop_newbie",
        "create_time","avg_price","min_price","max_price"));
    private static final Set<String> GOODS_FILTERS = new HashSet<>(Arrays.asList(
        "goods_key","shop_token","goods_type","name","price","market_price","status","shop_name"));
    private static final Set<String> SHOP_SORTS = new HashSet<>(Arrays.asList(
        "goods_count","sell_count","deposit_money","create_time","card_count",
        "article_count","resource_count","equity_count","auth_status",
        "avg_price","min_price","max_price","nickname","token"));
    private static final Set<String> GOODS_SORTS = new HashSet<>(Arrays.asList(
        "price","market_price","name","goods_type","status","shop_name"));

    public OfflineStore(Context ctx) throws Exception {
        context = ctx.getApplicationContext();
        dbPath = context.getDatabasePath("wzyp_snapshot_v1.db");
        File dir = dbPath.getParentFile();
        // Recover the prior database if the OS killed the app during an atomic swap.
        File pendingBackup=new File(dbPath.getAbsolutePath()+".backup");
        if(!dbPath.isFile() && pendingBackup.isFile() && !pendingBackup.renameTo(dbPath))
            throw new IllegalStateException("恢复上一次数据库备份失败");
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法建立本地数据目录");
        }
        if (!dbPath.isFile() || dbPath.length() < 1024) {
            File temp = new File(dbPath.getAbsolutePath() + ".part");
            try (InputStream in = context.getAssets().open("seed.db");
                 FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                out.getFD().sync();
            }
            if (!temp.renameTo(dbPath)) throw new IllegalStateException("离线数据解压失败");
        }
        db = SQLiteDatabase.openDatabase(dbPath.toString(), null, SQLiteDatabase.OPEN_READONLY);
        prefs = context.getSharedPreferences("wzyp_offline_chat", Context.MODE_PRIVATE);
    }

    /** Inspect and hot-swap a complete SQLite snapshot, preserving local chat and API settings.
     * All query calls hold this store's monitor. The caller must copy the candidate to
     * private storage and invoke this method on a worker thread.
     */
    public synchronized JSONObject installSnapshot(File candidate, String source) throws Exception {
        synchronized (OfflineStore.class) {
        if(candidate==null || !candidate.isFile() || candidate.length()<4096)
            throw new Exception("数据库文件为空或不是有效的 SQLite");
        if(candidate.length()>600L*1024L*1024L)
            throw new Exception("数据库超出 600 MB 限制");
        JSONObject info=checkSnapshot(candidate);
        File backup=new File(dbPath.getAbsolutePath()+".backup");
        if(backup.exists() && !backup.delete())throw new Exception("无法清理旧备份");
        db.close();
        boolean oldBackedUp=false;
        try {
            if(!dbPath.renameTo(backup))throw new Exception("旧数据库备份失败");
            oldBackedUp=true;
            if(!candidate.renameTo(dbPath))throw new Exception("新数据库移动失败");
            db=SQLiteDatabase.openDatabase(dbPath.toString(),null,SQLiteDatabase.OPEN_READONLY);
            // New snapshot must be visible immediately in dashboards, chat and model evidence.
            statsCache=null;
            prefs.edit().putString("snapshot_source",source)
                .putLong("snapshot_updated_ms",System.currentTimeMillis())
                .putInt("snapshot_shop_count",info.optInt("shops"))
                .putInt("snapshot_goods_count",info.optInt("goods")).apply();
            if(backup.exists())backup.delete();
            return info;
        } catch(Exception failure) {
            if(oldBackedUp) {
                if(dbPath.exists())dbPath.delete();
                if(!backup.renameTo(dbPath))
                    throw new Exception("更新失败且恢复原数据库失败，请保留 .backup 文件",failure);
            }
            db=SQLiteDatabase.openDatabase(dbPath.toString(),null,SQLiteDatabase.OPEN_READONLY);
            statsCache=null;
            throw failure;
        }
    }
    }

    /** Validation occurs before closing the active database. */
    private static JSONObject checkSnapshot(File file) throws Exception {
        SQLiteDatabase incoming=SQLiteDatabase.openDatabase(file.toString(),null,SQLiteDatabase.OPEN_READONLY);
        try {
            try(Cursor c=incoming.rawQuery("PRAGMA integrity_check",null)) {
                if(!c.moveToFirst() || !"ok".equalsIgnoreCase(c.getString(0)))
                    throw new Exception("SQLite 数据库完整性检查未通过");
            }
            String[][] required={
                {"wzyp_shops","token","nickname","login_province","contact_qq",
                    "sell_count","deposit_money","goods_count","card_count","article_count",
                    "resource_count","equity_count","auth_status","shop_newbie",
                    "create_time","fetch_done"},
                {"wzyp_goods","id","goods_key","shop_token","goods_type","name",
                    "price","market_price","status","link"},
                {"wzyp_goods_detail","goods_key","name","price","real_price",
                    "market_price","description","image"}
            };
            for(String[] table:required) {
                Set<String> found=new HashSet<>();
                try(Cursor c=incoming.rawQuery("PRAGMA table_info("+table[0]+")",null)) {
                    while(c.moveToNext())found.add(c.getString(1));
                }
                for(int i=1;i<table.length;i++)
                    if(!found.contains(table[i]))
                        throw new Exception("数据库缺少字段 "+table[0]+"."+table[i]);
            }
            JSONObject info=new JSONObject();
            try(Cursor c=incoming.rawQuery(
                "SELECT (SELECT COUNT(*) FROM wzyp_shops),"+
                "(SELECT COUNT(*) FROM wzyp_goods),"+
                "(SELECT COUNT(*) FROM wzyp_goods_detail)",null)) {
                if(!c.moveToFirst())throw new Exception("无法读取统计数量");
                put(info,"shops",c.getLong(0));
                put(info,"goods",c.getLong(1));
                put(info,"details",c.getLong(2));
            }
            if(info.optLong("shops")<1 || info.optLong("goods")<1)
                throw new Exception("导入的快照没有有效店铺或商品数据");
            return info;
        } finally {incoming.close();}
    }

    public synchronized JSONObject snapshotInfo() {
        JSONObject result=new JSONObject();
        put(result,"source",prefs.getString("snapshot_source","APK 内置历史快照"));
        put(result,"updated_ms",prefs.getLong("snapshot_updated_ms",0));
        put(result,"size_bytes",dbPath.length());
        put(result,"revision",syncRevision());
        JSONObject s=stats().optJSONObject("stats");
        put(result,"shops",s==null?0:s.optLong("shop_count"));
        put(result,"goods",s==null?0:s.optLong("goods_count"));
        put(result,"details",s==null?0:s.optLong("detail_count"));
        return result;
    }



    private static double toDouble(String raw){
        try{return Double.parseDouble(raw);}catch(Exception e){return Double.NaN;}
    }

    /** Existing-storefront token selection: only public shop tokens already in the local DB. */
    public synchronized JSONArray publicShopTokens(int offset,int limit) {
        JSONArray tokens=new JSONArray();
        try(Cursor cursor=db.rawQuery(
            "SELECT token FROM wzyp_shops WHERE token IS NOT NULL AND token!='' "+
            "ORDER BY token LIMIT ? OFFSET ?",
            new String[]{String.valueOf(Math.min(20,Math.max(1,limit))),String.valueOf(Math.max(0,offset))})) {
            while(cursor.moveToNext())tokens.put(cursor.getString(0));
        }
        return tokens;
    }

    /** Merge one successfully fetched storefront, without deleting unseen historical goods. */
    public synchronized JSONObject mergePublicShop(String token,JSONArray goods) throws Exception {
        if(token==null||!token.matches("[A-Za-z0-9_-]{3,80}"))
            throw new Exception("非法店铺 token");
        if(goods==null||goods.length()>1600)throw new Exception("店铺商品过多");
        synchronized(OfflineStore.class) {
            db.close();
            SQLiteDatabase writable=null;
            boolean committed=false;
            int added=0,modified=0,unchanged=0;
            try {
                writable=SQLiteDatabase.openDatabase(dbPath.toString(),null,SQLiteDatabase.OPEN_READWRITE);
                writable.beginTransaction();
                boolean newShop=false;
                try(Cursor shop=writable.rawQuery(
                    "SELECT token FROM wzyp_shops WHERE token=?",new String[]{token})) {
                    newShop=!shop.moveToFirst();
                }
                if(newShop) {
                    if(goods.length()==0)throw new Exception("新店铺没有公开商品，无法确定店铺是否有效");
                    JSONObject first=goods.optJSONObject(0);
                    String nickname=first==null?"":first.optString("shop_name",token).trim();
                    if(nickname.isEmpty()||nickname.length()>120)nickname=token;
                    writable.execSQL("INSERT INTO wzyp_shops (token,nickname,goods_count,"+
                        "fetch_done,link,create_time) VALUES (?,?,?,?,?,?)",
                        new Object[]{token,nickname,0,0,"https://wzyp.cn/shop/"+token,
                            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.ROOT)
                                .format(new java.util.Date())});
                }
                long nextId=1;
                try(Cursor id=writable.rawQuery(
                    "SELECT COALESCE(MAX(CAST(id AS INTEGER)),0)+1 FROM wzyp_goods",null)){
                    if(id.moveToFirst())nextId=id.getLong(0);
                }
                // Preload this shop's history once; avoid one Cursor query for each
                // unchanged item when refreshing hundreds of catalog entries.
                java.util.HashMap<String,String[]> previous=new java.util.HashMap<>();
                try(Cursor existing=writable.rawQuery(
                    "SELECT goods_key,name,price,market_price,status,goods_type "+
                    "FROM wzyp_goods WHERE shop_token=?",new String[]{token})) {
                    while(existing.moveToNext()){
                        String[] row=new String[5];
                        for(int j=0;j<5;j++)row[j]=existing.isNull(j+1)?"":existing.getString(j+1);
                        previous.put(existing.getString(0),row);
                    }
                }
                java.util.HashSet<String> seen=new java.util.HashSet<>();
                for(int i=0;i<goods.length();i++){
                    JSONObject row=goods.optJSONObject(i);
                    if(row==null)throw new Exception("商品格式错误");
                    String key=row.optString("goods_key","");
                    if(!key.matches("[A-Za-z0-9_-]{3,80}")||!seen.add(key))
                        throw new Exception("商品 Key 无效或重复");
                    String name=row.optString("name","").trim();
                    String type=row.optString("goods_type","card");
                    if(name.isEmpty() || name.length()>600 || !Arrays.asList(
                       "card","article","resource","equity").contains(type))
                        throw new Exception("商品名称或类型异常");
                    double price=row.optDouble("price",Double.NaN);
                    if(!Double.isFinite(price)||price<0||price>9999999)
                        throw new Exception("商品价格异常");
                    Object market=row.opt("market_price");
                    String status=row.optString("status","1");
                    String link="https://wzyp.cn/item/"+key;
                    String marketText=market==null||market==JSONObject.NULL?"":String.valueOf(market);
                    String[] old=previous.get(key);
                    boolean exists=old!=null;
                    boolean changed=!exists ||
                        !name.equals(old[0]) ||
                        Double.compare(price,toDouble(old[1]))!=0 ||
                        !marketText.equals(old[2]) ||
                        !status.equals(old[3]) ||
                        !type.equals(old[4]);
                    if(exists && !changed){unchanged++;continue;}
                    if(!exists){
                        // goods_key is a global item identifier. Never silently overwrite
                        // another shop's record when an upstream catalog is inconsistent.
                        try(Cursor duplicate=writable.rawQuery(
                            "SELECT shop_token FROM wzyp_goods WHERE goods_key=? LIMIT 1",
                            new String[]{key})){
                            if(duplicate.moveToFirst())
                                throw new Exception("商品 Key 已属于其他店铺："+key);
                        }
                    }
                    JSONObject raw=new JSONObject();
                    put(raw,"source","public-storefront");
                    put(raw,"fetched_at",System.currentTimeMillis());
                    put(raw,"goods_key",key);
                    put(raw,"goods_type",type);
                    put(raw,"name",name);
                    put(raw,"price",price);
                    if(exists){
                        writable.execSQL("UPDATE wzyp_goods SET name=?,price=?,market_price=?,status=?,"+
                            "goods_type=?,link=?,raw_json=? WHERE goods_key=?",
                            new Object[]{name,price,marketText,status,type,link,raw.toString(),key});
                        modified++;
                    }else{
                        writable.execSQL("INSERT INTO wzyp_goods ("+
                            "id,goods_key,shop_token,goods_type,name,price,market_price,status,link,raw_json) "+
                            "VALUES (?,?,?,?,?,?,?,?,?,?)",
                            new Object[]{nextId++,key,token,type,name,price,marketText,status,link,raw.toString()});
                        added++;
                    }
                }
                writable.execSQL("UPDATE wzyp_shops SET goods_count=("+
                    "SELECT COUNT(*) FROM wzyp_goods WHERE shop_token=?),fetch_done=1 WHERE token=?",
                    new Object[]{token,token});
                writable.setTransactionSuccessful();
                committed=true;
                JSONObject result=new JSONObject();
                put(result,"shop_token",token);put(result,"new_shop",newShop);
                put(result,"added",added);
                put(result,"modified",modified);put(result,"unchanged",unchanged);
                put(result,"seen",goods.length());
                return result;
            }finally {
                if(writable!=null){
                    if(writable.inTransaction())writable.endTransaction();
                    writable.close();
                }
                db=SQLiteDatabase.openDatabase(dbPath.toString(),null,SQLiteDatabase.OPEN_READONLY);
                if(committed){statsCache=null;statsRevision=null;}
            }
        }
    }

    /** Apply a versioned JSON delta in a single SQLite transaction. Model-generated SQL is never accepted. */
    public synchronized JSONObject applyDelta(JSONObject feed) throws Exception {
        synchronized (OfflineStore.class) {
        if(feed.optInt("schema_version",-1)!=1)throw new Exception("不支持的数据源协议版本");
        String revision=feed.optString("revision","").trim();
        if(revision.isEmpty()||revision.length()>128)throw new Exception("同步数据缺少合法 revision");
        JSONObject changes=feed.optJSONObject("changes");
        if(changes==null)throw new Exception("同步响应缺少 changes");
        String[] tables={"wzyp_shops","wzyp_goods","wzyp_goods_detail"};
        String[] keys={"token","goods_key","goods_key"};
        java.util.Iterator<String> changeKeys=changes.keys();
        while(changeKeys.hasNext()) {
            String key=changeKeys.next();
            if(!Arrays.asList(tables).contains(key))throw new Exception("同步响应包含未知表："+key);
        }
        // All local reads and model evidence are locked out while the transaction runs.
        db.close();
        SQLiteDatabase writable=null;
        boolean committed=false;
        try {
            writable=SQLiteDatabase.openDatabase(dbPath.toString(),null,SQLiteDatabase.OPEN_READWRITE);
            writable.beginTransaction();
            writable.execSQL("CREATE TABLE IF NOT EXISTS _wzyp_sync_meta (k TEXT PRIMARY KEY, v TEXT)");
            String current="";
            try(Cursor cur=writable.rawQuery(
                  "SELECT v FROM _wzyp_sync_meta WHERE k='revision'",null)) {
                if(cur.moveToFirst())current=cur.getString(0);
            }
            if(revision.equals(current)) {
                JSONObject unchanged=new JSONObject();
                put(unchanged,"status","unchanged");
                put(unchanged,"revision",revision);
                writable.setTransactionSuccessful();
                committed=true;
                return unchanged;
            }
            String base=feed.optString("base_revision","");
            if(!base.equals(current))
                throw new Exception("同步版本不匹配：手机="+(current.isEmpty()?"初始快照":current)+
                    "，数据源基线="+base+"。请先导入完整数据库或正确的增量链");
            int total=0,deleted=0,upserted=0;
            // First delete obsolete detail/goods/shop rows, then upsert parents first.
            for(int index=2;index>=0;index--) {
                String table=tables[index],key=keys[index];
                JSONObject change=changes.optJSONObject(table);
                if(change==null)continue;
                JSONArray deletions=change.optJSONArray("delete");
                if(deletions==null)continue;
                if(deletions.length()>25000 || (total+=deletions.length())>45000)
                    throw new Exception("同步包记录超过限制");
                for(int i=0;i<deletions.length();i++){
                    String id=deletions.optString(i,"");
                    if(id.isEmpty()||id.length()>300)throw new Exception("删除记录键不合法");
                    writable.execSQL("DELETE FROM "+table+" WHERE "+key+"=?",new Object[]{id});
                    deleted++;
                }
            }
            for(int index=0;index<3;index++){
                String table=tables[index],key=keys[index];
                JSONObject change=changes.optJSONObject(table);
                if(change==null)continue;
                JSONArray updates=change.optJSONArray("upsert");
                if(updates==null)continue;
                if(updates.length()>25000 || (total+=updates.length())>45000)
                    throw new Exception("同步包记录超过限制");
                HashSet<String> columns=new HashSet<>();
                try(Cursor cur=writable.rawQuery("PRAGMA table_info("+table+")",null)) {
                    while(cur.moveToNext())columns.add(cur.getString(1));
                }
                for(int i=0;i<updates.length();i++){
                    JSONObject row=updates.optJSONObject(i);
                    if(row==null)throw new Exception("更新记录必须是 JSON 对象");
                    String id=row.optString(key,"");
                    if(id.isEmpty()||id.length()>300)throw new Exception(table+" 缺少 "+key);
                    JSONArray jsonNames=row.names();
                    if(jsonNames==null || jsonNames.length()==0)throw new Exception("空更新记录");
                    String[] names=new String[jsonNames.length()];
                    for(int ni=0;ni<names.length;ni++)names[ni]=jsonNames.getString(ni);
                    for(String column:names)
                        if(!columns.contains(column))throw new Exception("数据库没有字段 "+table+"."+column);
                    // Remove existing key (seed database does not declare primary keys).
                    writable.execSQL("DELETE FROM "+table+" WHERE "+key+"=?",new Object[]{id});
                    StringBuilder sql=new StringBuilder("INSERT INTO "+table+" (");
                    StringBuilder qmarks=new StringBuilder();
                    Object[] vals=new Object[names.length];
                    for(int col=0;col<names.length;col++){
                        if(col>0){sql.append(",");qmarks.append(",");}
                        sql.append("\"").append(names[col]).append("\"");
                        qmarks.append("?");
                        Object val=row.opt(names[col]);
                        if(val==null||JSONObject.NULL.equals(val))vals[col]=null;
                        else if(val instanceof Number||val instanceof String)vals[col]=val;
                        else if(val instanceof Boolean)vals[col]=((Boolean)val)?1:0;
                        else throw new Exception("数据库字段仅支持文本、数字或 null");
                    }
                    sql.append(") VALUES (").append(qmarks).append(")");
                    writable.execSQL(sql.toString(),vals);
                    upserted++;
                }
            }
            writable.execSQL("INSERT OR REPLACE INTO _wzyp_sync_meta(k,v) VALUES ('revision',?)",
                new Object[]{revision});
            writable.setTransactionSuccessful();
            committed=true;
            statsCache=null;
            JSONObject summary=new JSONObject();
            put(summary,"status","updated");
            put(summary,"revision",revision);
            put(summary,"deleted",deleted);
            put(summary,"upserted",upserted);
            return summary;
        }finally{
            if(writable!=null){
                if(writable.inTransaction())writable.endTransaction();
                writable.close();
            }
            db=SQLiteDatabase.openDatabase(dbPath.toString(),null,SQLiteDatabase.OPEN_READONLY);
            if(committed)statsCache=null;
        }
    }
    }

    public synchronized String syncRevision() {
        try(Cursor c=db.rawQuery("SELECT v FROM _wzyp_sync_meta WHERE k='revision'",null)){
            return c.moveToFirst()?c.getString(0):"";
        }catch(Exception e){return "";}
    }

    private static JSONObject object(String s) {
        try { return new JSONObject(s == null ? "{}" : s); }
        catch (JSONException e) { return new JSONObject(); }
    }

    private static JSONArray array(JSONObject obj, String key) {
        JSONArray out = obj.optJSONArray(key);
        return out == null ? new JSONArray() : out;
    }

    private static JSONObject put(JSONObject o, String key, Object v) {
        try { o.put(key, v); } catch (JSONException ignored) {}
        return o;
    }

    private JSONObject one(String sql, List<String> args) {
        JSONArray items = rows(sql, args);
        return items.length() == 0 ? new JSONObject() : items.optJSONObject(0);
    }

    private JSONArray rows(String sql, List<String> args) {
        JSONArray out = new JSONArray();
        Cursor cursor = db.rawQuery(sql, args.toArray(new String[0]));
        try {
            String[] names = cursor.getColumnNames();
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                for (int i = 0; i < names.length; i++) {
                    try {
                        switch (cursor.getType(i)) {
                            case Cursor.FIELD_TYPE_INTEGER: row.put(names[i], cursor.getLong(i)); break;
                            case Cursor.FIELD_TYPE_FLOAT: row.put(names[i], cursor.getDouble(i)); break;
                            case Cursor.FIELD_TYPE_STRING: row.put(names[i], cursor.getString(i)); break;
                            default: row.put(names[i], JSONObject.NULL); break;
                        }
                    } catch (JSONException ignored) {}
                }
                out.put(row);
            }
        } finally { cursor.close(); }
        return out;
    }

    private static List<String> args(String... input) {
        return new ArrayList<>(Arrays.asList(input));
    }

    private static final class Where {
        StringBuilder sql = new StringBuilder("1=1");
        List<String> args = new ArrayList<>();
        void add(String expr, String... values) {
            sql.append(" AND (").append(expr).append(")");
            args.addAll(Arrays.asList(values));
        }
    }

    private void appendFilters(Where where, JSONArray filters, Set<String> whitelist) {
        Map<String,String> ops = new HashMap<>();
        ops.put("gt", ">"); ops.put("gte", ">="); ops.put("lt", "<");
        ops.put("lte", "<="); ops.put("eq", "="); ops.put("ne", "!=");
        ops.put("like", "LIKE"); ops.put("in", "IN");
        for (int i=0; i<filters.length(); i++) {
            JSONObject f = filters.optJSONObject(i);
            if (f == null) continue;
            String col = f.optString("field","");
            String op = ops.get(f.optString("op",""));
            Object v = f.opt("value");
            if (!whitelist.contains(col) || op == null || v == null || v == JSONObject.NULL ||
                    String.valueOf(v).isEmpty()) continue;
            if ("LIKE".equals(op)) where.add(col+" LIKE ?", "%"+v+"%");
            else if ("IN".equals(op)) {
                List<String> parts = new ArrayList<>();
                if (v instanceof JSONArray) {
                    JSONArray a=(JSONArray)v;
                    for (int k=0;k<a.length();k++) parts.add(a.optString(k));
                } else for (String p : String.valueOf(v).split(",")) {
                    if (!p.trim().isEmpty()) parts.add(p.trim());
                }
                if (parts.isEmpty()) continue;
                String[] marks=new String[parts.size()];
                Arrays.fill(marks, "?");
                where.add(col+" IN ("+TextUtils.join(",",marks)+")",parts.toArray(new String[0]));
            } else where.add(col+" "+op+" ?", String.valueOf(v));
        }
    }

    private JSONObject listShops(JSONObject q) {
        String base="SELECT s.*, COALESCE(g.fetched_count,0) AS fetched_count,"+
                " g.min_price AS min_price,g.max_price AS max_price,g.avg_price AS avg_price"+
                " FROM wzyp_shops s LEFT JOIN ("+
                " SELECT shop_token,COUNT(*) AS fetched_count, MIN(price) AS min_price,"+
                " MAX(price) AS max_price, ROUND(AVG(price),2) AS avg_price"+
                " FROM wzyp_goods GROUP BY shop_token) g ON g.shop_token=s.token";
        Where w = new Where();
        appendFilters(w, array(q,"filters"), SHOP_FILTERS);
        String kw=q.optString("kw", "").trim();
        if (!kw.isEmpty() && !"null".equals(kw)) {
            w.add("(token LIKE ? OR nickname LIKE ? OR contact_qq LIKE ?)",
                    "%"+kw+"%","%"+kw+"%","%"+kw+"%");
        }
        String fetch=q.optString("fetch_status","");
        if ("done".equals(fetch)) w.add("fetch_done=1 AND goods_count>0");
        if ("fetching".equals(fetch)) w.add("fetch_done=0 AND goods_count>0");
        if ("empty".equals(fetch)) w.add("goods_count=0");

        String source="("+base+") src";
        int total=one("SELECT COUNT(*) AS c FROM "+source+" WHERE "+w.sql,w.args).optInt("c");
        String sort=q.optString("sort","goods_count");
        if(!SHOP_SORTS.contains(sort))sort="goods_count";
        String order="asc".equalsIgnoreCase(q.optString("order","desc"))?"ASC":"DESC";
        int limit=Math.max(1,Math.min(q.optInt("pageSize",20),500));
        int page=Math.max(1,q.optInt("page",1));
        List<String> sqlArgs=new ArrayList<>(w.args);
        sqlArgs.add(String.valueOf(limit));sqlArgs.add(String.valueOf((page-1)*limit));
        JSONArray data=rows("SELECT * FROM "+source+" WHERE "+w.sql+
                " ORDER BY "+sort+" "+order+",token ASC LIMIT ? OFFSET ?",sqlArgs);
        // sqlite date values are stored as text and can include time
        for(int i=0;i<data.length();i++){
            JSONObject row=data.optJSONObject(i);
            if(row==null)continue;
            String date=row.optString("create_time","");
            if(date.length()>10)put(row,"create_time",date.substring(0,10));
        }
        return put(put(new JSONObject(),"total",total),"rows",data);
    }

    private JSONObject listGoods(JSONObject q) {
        String base="SELECT g.*,s.nickname AS shop_name FROM wzyp_goods g "+
                    " JOIN wzyp_shops s ON s.token=g.shop_token";
        Where w=new Where();
        appendFilters(w,array(q,"filters"),GOODS_FILTERS);
        for(String key : new String[]{"shop_token","goods_type"}) {
            String v=q.optString(key,"");
            if(!v.isEmpty() && !"null".equals(v))w.add(key+"=?",v);
        }
        String kw=q.optString("kw","").trim();
        if(!kw.isEmpty() && !"null".equals(kw))w.add("name LIKE ?","%"+kw+"%");
        if(!q.isNull("price_min") && q.has("price_min")) {
            String min=q.optString("price_min","");
            if(!min.isEmpty()) w.add("price>=?",min);
        }
        if(!q.isNull("price_max") && q.has("price_max")) {
            String max=q.optString("price_max","");
            if(!max.isEmpty()) w.add("price<=?",max);
        }
        String source="("+base+") src";
        int total=one("SELECT COUNT(*) AS c FROM "+source+" WHERE "+w.sql,w.args).optInt("c");
        String sort=q.optString("sort","price");
        if(!GOODS_SORTS.contains(sort))sort="price";
        String order="desc".equalsIgnoreCase(q.optString("order","asc"))?"DESC":"ASC";
        int limit=Math.max(1,Math.min(q.optInt("pageSize",20),500));
        int page=Math.max(1,q.optInt("page",1));
        List<String> sqlArgs=new ArrayList<>(w.args);
        sqlArgs.add(String.valueOf(limit));sqlArgs.add(String.valueOf((page-1)*limit));
        JSONArray data=rows("SELECT * FROM "+source+" WHERE "+w.sql+
                " ORDER BY "+sort+" "+order+",id ASC LIMIT ? OFFSET ?",sqlArgs);
        return put(put(new JSONObject(),"total",total),"rows",data);
    }

    private JSONObject goodsDetail(String key) {
        JSONObject row=one("SELECT * FROM wzyp_goods_detail WHERE goods_key=?",args(key));
        if (row.length()==0) row=one("SELECT * FROM wzyp_goods WHERE goods_key=?",args(key));
        // Treat upstream HTML as untrusted. Render as escaped text and never inject remote scripts.
        String desc=row.optString("description","");
        if(!desc.isEmpty()){
            String safe=TextUtils.htmlEncode(
                Html.fromHtml(desc, Html.FROM_HTML_MODE_LEGACY).toString().trim());
            put(row,"description",safe.replace("\n","<br>"));
        }
        put(row,"image","");
        return row;
    }

    private JSONObject stats() {
        String revision=syncRevision();
        if(statsCache!=null && revision.equals(statsRevision))return statsCache;
        statsCache=null;
        JSONObject s=one("SELECT "+
                "(SELECT COUNT(*) FROM wzyp_shops) AS shop_count,"+
                "(SELECT COUNT(*) FROM wzyp_goods) AS goods_count,"+
                "(SELECT COUNT(*) FROM wzyp_goods_detail) AS detail_count,"+
                "(SELECT COALESCE(SUM(sell_count),0) FROM wzyp_shops) AS total_sell,"+
                "(SELECT COALESCE(SUM(deposit_money),0) FROM wzyp_shops) AS total_deposit,"+
                "(SELECT ROUND(AVG(price),2) FROM wzyp_goods) AS avg_price,"+
                "(SELECT MIN(price) FROM wzyp_goods) AS min_price,"+
                "(SELECT MAX(price) FROM wzyp_goods) AS max_price",Collections.emptyList());
        JSONArray typeDist=rows("SELECT goods_type,COUNT(*) AS count,ROUND(AVG(price),2) AS avg_price "+
            "FROM wzyp_goods GROUP BY goods_type",Collections.emptyList());
        JSONArray buckets=rows("SELECT CASE WHEN price<10 THEN '<10' WHEN price<50 THEN '10-50' "+
            "WHEN price<100 THEN '50-100' WHEN price<500 THEN '100-500' "+
            "WHEN price<2000 THEN '500-2000' ELSE '>=2000' END AS bucket,COUNT(*) AS count "+
            "FROM wzyp_goods WHERE price IS NOT NULL GROUP BY bucket "+
            "ORDER BY CASE bucket WHEN '<10' THEN 1 WHEN '10-50' THEN 2 WHEN '50-100' THEN 3 "+
            "WHEN '100-500' THEN 4 WHEN '500-2000' THEN 5 ELSE 6 END",Collections.emptyList());

        LinkedHashMap<String,Integer> frequency=new LinkedHashMap<>();
        Cursor c=db.rawQuery("SELECT name FROM wzyp_goods",new String[0]);
        Pattern keywords=Pattern.compile("[A-Za-z]{3,12}|[\\u4e00-\\u9fa5]{2,4}");
        try {
            while(c.moveToNext()) {
                String name=c.getString(0);
                if(name==null)continue;
                Matcher m=keywords.matcher(name);
                while(m.find()){
                    String word=m.group();
                    frequency.put(word,frequency.getOrDefault(word,0)+1);
                }
            }
        } finally {c.close();}
        List<Map.Entry<String,Integer>> ranking=new ArrayList<>(frequency.entrySet());
        ranking.sort((a,b)->Integer.compare(b.getValue(),a.getValue()));
        JSONArray words=new JSONArray();
        for(int i=0;i<Math.min(24,ranking.size());i++){
            Map.Entry<String,Integer> entry=ranking.get(i);
            words.put(put(put(new JSONObject(),"word",entry.getKey()),"count",entry.getValue()));
        }
        statsCache=new JSONObject();
        put(statsCache,"stats",s);
        put(statsCache,"type_dist",typeDist);
        put(statsCache,"price_buckets",buckets);
        put(statsCache,"top_keywords",words);
        statsRevision=revision;
        return statsCache;
    }

    private String renderRows(String lead,JSONArray rows,String kind) {
        StringBuilder out=new StringBuilder(lead);
        if(rows.length()==0)return out.append("\n\n没有符合条件的本地数据。").toString();
        for(int i=0;i<rows.length();i++) {
            JSONObject r=rows.optJSONObject(i);
            if(r==null)continue;
            out.append("\n").append(i+1).append(". ");
            if("shop".equals(kind)) out.append(r.optString("nickname","(未命名)"))
                 .append("：销量 ").append(r.optLong("sell_count"))
                 .append("，商品 ").append(r.optLong("goods_count")).append(" 件");
            else out.append(r.optString("name","(未命名)"))
                 .append("：¥").append(String.format(Locale.CHINA,"%.2f",r.optDouble("price",0)))
                 .append("（").append(r.optString("shop_name","")).append("）");
        }
        return out.toString();
    }

    private String localAnalysis(String question) {
        JSONObject stat=stats().optJSONObject("stats");
        String q=question==null?"":question.trim();
        String normalized=q.replace("？","?").replace("～","~");
        String type="";
        if(q.contains("卡密"))type="card";
        else if(q.contains("权益"))type="equity";
        else if(q.contains("资源"))type="resource";
        else if(q.contains("文章"))type="article";
        if(q.contains("这份店铺查询结果")||q.contains("这份商品查询结果")){
            int a=q.indexOf("<查询上下文>");
            int b=q.indexOf("</查询上下文>");
            if(a>=0 && b>a) {
                JSONObject ctx=object(q.substring(a+7,b).trim());
                JSONArray values=ctx.optJSONArray("rows");
                if(values!=null) {
                    int total=ctx.optInt("total",values.length());
                    StringBuilder sb=new StringBuilder("**本地查询解读**：共匹配 "+total+" 条，已读取列表前 "+values.length()+" 条记录。\n");
                    int n=Math.min(values.length(),5);
                    for(int i=0;i<n;i++){
                        JSONObject o=values.optJSONObject(i);
                        if(o!=null)sb.append("\n- ").append(o.optString("店铺",o.optString("商品","-")))
                              .append("：").append(o.optString("销量",""))
                              .append(" ").append(o.optString("价格",""));
                    }
                    return sb.append("\n\n以上数据直接来自手机本地 SQLite 快照，没有联网。").toString();
                }
            }
        }
        if((q.contains("价格")||q.contains("商品")) &&
           (q.contains("区间")||q.contains("之间"))){
            Matcher range=Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:~|至|到|-)\\s*(\\d+(?:\\.\\d+)?)").matcher(normalized);
            if(range.find()){
                JSONArray rs=rows("SELECT s.nickname,COUNT(*) AS goods_count,"+
                    "ROUND(AVG(g.price),2) AS avg_price FROM wzyp_goods g "+
                    "JOIN wzyp_shops s ON s.token=g.shop_token WHERE g.price BETWEEN ? AND ? "+
                    "GROUP BY g.shop_token ORDER BY goods_count DESC LIMIT 5",
                    args(range.group(1),range.group(2)));
                StringBuilder sb=new StringBuilder("价格区间 ¥"+range.group(1)+"～¥"+range.group(2)+" 的店铺（按商品数）：");
                for(int i=0;i<rs.length();i++){
                    JSONObject x=rs.optJSONObject(i);
                    sb.append("\n").append(i+1).append(". ").append(x.optString("nickname"))
                      .append("：").append(x.optInt("goods_count")).append(" 件，均价 ¥")
                      .append(String.format(Locale.CHINA,"%.2f",x.optDouble("avg_price")));
                }
                return sb.toString();
            }
        }
        if(q.contains("类型对比")||q.contains("品类")||q.contains("类型分布")||
              (q.contains("卡密")&&q.contains("权益")&&q.contains("资源"))){
            JSONArray dist=stats().optJSONArray("type_dist");
            StringBuilder sb=new StringBuilder("**本地商品类型统计**");
            for(int i=0;i<dist.length();i++){
                JSONObject row=dist.optJSONObject(i);
                String k=row.optString("goods_type");
                String zh=k.equals("card")?"卡密":k.equals("equity")?"权益":k.equals("resource")?"资源":"文章";
                sb.append("\n- ").append(zh).append("：").append(row.optInt("count"))
                  .append(" 件，均价 ¥").append(String.format(Locale.CHINA,"%.2f",row.optDouble("avg_price")));
            }
            return sb.toString();
        }
        if(q.contains("最贵")||q.contains("最高价")){
            return renderRows("**本地价格最高的 5 件商品**",
                rows("SELECT g.name,g.price,s.nickname AS shop_name FROM wzyp_goods g "+
                  "JOIN wzyp_shops s ON s.token=g.shop_token "+
                  "ORDER BY g.price DESC LIMIT 5",Collections.emptyList()),"goods");
        }
        if(q.contains("最低价")||q.contains("最便宜")){
            return renderRows("**本地价格最低的 5 件商品**",
                rows("SELECT g.name,g.price,s.nickname AS shop_name FROM wzyp_goods g "+
                  "JOIN wzyp_shops s ON s.token=g.shop_token "+
                  "ORDER BY g.price ASC LIMIT 5",Collections.emptyList()),"goods");
        }
        if(q.contains("销量")&&(q.contains("排行")||q.contains("Top")||q.contains("top")||
           q.contains("前")||q.contains("最多")||q.contains("排名"))){
            return renderRows("**店铺销量 TOP 5**",rows("SELECT nickname,sell_count,goods_count "+
                    "FROM wzyp_shops ORDER BY sell_count DESC LIMIT 5",Collections.emptyList()),"shop");
        }
        if(q.contains("商品数")&&(q.contains("排名")||q.contains("排行")||q.contains("前")||q.contains("最多"))){
            return renderRows("**店铺商品数 TOP 5**",rows("SELECT nickname,sell_count,goods_count "+
                    "FROM wzyp_shops ORDER BY goods_count DESC LIMIT 5",Collections.emptyList()),"shop");
        }
        if(q.contains("平均价")||q.contains("均价")) {
            if(!type.isEmpty()){
                JSONObject r=one("SELECT COUNT(*) AS c,ROUND(AVG(price),2) AS p FROM wzyp_goods "+
                    "WHERE goods_type=?",args(type));
                return "该品类共有 "+r.optInt("c")+" 件，平均价 ¥"+
                    String.format(Locale.CHINA,"%.2f",r.optDouble("p"))+"（来自离线数据库）。";
            }
            return "全部商品均价：¥"+String.format(Locale.CHINA,"%.2f",stat.optDouble("avg_price"))+
                   "；最低价 ¥"+String.format(Locale.CHINA,"%.2f",stat.optDouble("min_price"))+
                   "；最高价 ¥"+String.format(Locale.CHINA,"%.2f",stat.optDouble("max_price"))+"。";
        }
        if(q.contains("多少")||q.contains("总数")||q.contains("数量")||q.contains("统计")){
            if(!type.isEmpty()){
                JSONObject r=one("SELECT COUNT(*) AS c FROM wzyp_goods WHERE goods_type=?",args(type));
                return "本地快照中该类型商品有 **"+r.optInt("c")+" 件**。";
            }
            return "**本地数据规模**："+stat.optInt("shop_count")+" 家店铺，"+
                stat.optInt("goods_count")+" 件商品，"+stat.optInt("detail_count")+
                " 条商品详情，累计销量 "+stat.optLong("total_sell")+"。";
        }
        return "当前使用的是**手机本地规则分析引擎**，可以离线查询真实数据，但不具备云端大模型的开放式生成能力。\n\n"+
            "你可以问：\n- 各店铺商品数排名？\n- 销量 Top5 店铺分析\n- 最贵的10个商品\n"+
            "- 卡密/权益/资源类型对比\n- 价格100~300之间哪些店铺商品最多？\n- 商品均价是多少？";
    }

    private static JSONArray history(SharedPreferences prefs,String tid) {
        try { return new JSONArray(prefs.getString("thread_"+tid,"[]")); }
        catch (JSONException e) { return new JSONArray(); }
    }

    private JSONObject chat(JSONObject body) {
        String tid=body.optString("thread_id","default");
        String q=body.optString("message","");
        JSONArray h=history(prefs,tid);
        h.put(put(put(new JSONObject(),"role","human"),"content",q));
        String reply=localAnalysis(q);
        h.put(put(put(new JSONObject(),"role","ai"),"content",reply));
        JSONArray keep=new JSONArray();
        for(int i=Math.max(0,h.length()-100);i<h.length();i++)keep.put(h.opt(i));
        prefs.edit().putString("thread_"+tid,keep.toString()).apply();
        JSONObject out=put(put(new JSONObject(),"reply",reply),"followups",new JSONArray());
        return out;
    }


    /** Android shares a JSON backup through the OS chooser; no network request is performed by the app. */
    @JavascriptInterface
    public void shareText(String content) {
        if(content == null || content.length()>3000000) return;
        android.content.Intent send=new android.content.Intent(android.content.Intent.ACTION_SEND);
        send.setType("application/json");
        send.putExtra(android.content.Intent.EXTRA_TEXT,content);
        android.content.Intent chooser=android.content.Intent.createChooser(send,"导出离线数据备份");
        chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(chooser);
    }

    @JavascriptInterface
    public synchronized String request(String path, String bodyString) {
        try {
            JSONObject body=object(bodyString);
            if(path.equals("/api/stats"))return stats().toString();
            if(path.equals("/api/data/info"))return snapshotInfo().toString();
            if(path.equals("/api/shops"))return listShops(body).toString();
            if(path.equals("/api/goods"))return listGoods(body).toString();
            if(path.startsWith("/api/goods/"))
                return goodsDetail(android.net.Uri.decode(path.substring("/api/goods/".length()))).toString();
            if(path.equals("/api/llm/test"))return "{\"ok\":true,\"reply\":\"离线分析引擎可用\"}";
            if(path.equals("/api/chat"))return chat(body).toString();
            if(path.startsWith("/api/chat/history")){
                String id=android.net.Uri.parse("https://local.invalid"+path).getQueryParameter("thread_id");
                return put(new JSONObject(),"messages",history(prefs,id==null?"default":id)).toString();
            }
            if(path.startsWith("/api/chat/clear")){
                String id=android.net.Uri.parse("https://local.invalid"+path).getQueryParameter("thread_id");
                prefs.edit().remove("thread_"+(id==null?"default":id)).apply();
                return "{\"ok\":true}";
            }
            if(path.equals("/api/chat/delete")){
                prefs.edit().remove("thread_"+body.optString("thread_id","")).apply();
                return "{\"ok\":true}";
            }
            if(path.equals("/api/chat/restore")){
                String id=body.optString("thread_id","");
                JSONArray msgs=array(body,"messages");
                if(id.length()>0 && id.length()<100 && msgs.length()<=100)
                    prefs.edit().putString("thread_"+id,msgs.toString()).apply();
                return "{\"ok\":true}";
            }
            return put(new JSONObject(),"_error","unsupported offline endpoint: "+path).toString();
        } catch (Exception e) {
            return put(new JSONObject(),"_error",e.getClass().getSimpleName()+": "+e.getMessage()).toString();
        }
    }
}
