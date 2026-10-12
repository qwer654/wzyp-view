package cn.wzyp.view;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * OpenAI Chat Completions compatible HTTP client, separate from the local SQLite store.
 * Only Java native code can access the encrypted API key. The WebView itself stays offline.
 */
public final class AiApiClient {
    private static final String KEY_ALIAS="wzyp_ai_key_v3";
    private static final String STORE_NAME="wzyp_api_settings_v3";
    private static final String BASE="base_url";
    private static final String MODEL="model";
    private static final String MODE="mode";
    private static final String SECRET="api_key_encrypted";
    private final SharedPreferences prefs;

    public AiApiClient(Context context) {
        prefs=context.getApplicationContext().getSharedPreferences(STORE_NAME,Context.MODE_PRIVATE);
    }

    private static JSONObject put(JSONObject j,String k,Object v) {
        try { j.put(k,v); } catch(JSONException ignored){}
        return j;
    }

    public synchronized JSONObject settings() {
        JSONObject r=new JSONObject();
        put(r,"baseurl",prefs.getString(BASE,""));
        put(r,"model",prefs.getString(MODEL,""));
        put(r,"mode",prefs.getString(MODE,"api"));
        put(r,"has_key",!prefs.getString(SECRET,"").isEmpty());
        return r;
    }

    public synchronized JSONObject save(JSONObject data) throws Exception {
        String mode=data.optString("mode",prefs.getString(MODE,"api"));
        if(!"local".equals(mode)&&!"api".equals(mode))throw new Exception("模式只能是 api 或 local");
        String base=data.optString("baseurl",prefs.getString(BASE,"")).trim();
        String model=data.optString("model",prefs.getString(MODEL,"")).trim();
        if(base.length()>1000 || model.length()>160)throw new Exception("接口地址或模型名称太长");
        if(!base.isEmpty()) endpoint(base);  // URL validation before saving
        if(base.isEmpty() && "api".equals(mode) && !model.isEmpty())
            throw new Exception("请先填写 Base URL");
        SharedPreferences.Editor editor=prefs.edit()
            .putString(BASE,base).putString(MODEL,model).putString(MODE,mode);
        if(data.optBoolean("clear_key",false)) editor.remove(SECRET);
        else {
            String key=data.optString("apikey","").trim();
            if(!key.isEmpty()) {
                if(key.length()>4096)throw new Exception("API Key 长度超限");
                editor.putString(SECRET,encrypt(key));
            }
        }
        if(!editor.commit())throw new Exception("保存配置失败");
        return put(settings(),"ok",true);
    }

    public synchronized boolean useApi() {
        return !"local".equals(prefs.getString(MODE,"api"));
    }

    public synchronized boolean isConfigured() {
        return !prefs.getString(BASE,"").isEmpty() && !prefs.getString(MODEL,"").isEmpty();
    }

    private static boolean privateHost(String host) {
        if(host==null)return false;
        String h=host.toLowerCase(Locale.ROOT);
        if("localhost".equals(h)||"127.0.0.1".equals(h)||"::1".equals(h)
                ||h.endsWith(".localhost")||h.endsWith(".local"))return true;
        // Only literals: never DNS-resolve arbitrary public names before validating HTTPS.
        if(!h.matches("[0-9.]+"))return false;
        String[] parts=h.split("\\.");
        if(parts.length!=4)return false;
        int[] ip=new int[4];
        try {
            for(int i=0;i<4;i++){ip[i]=Integer.parseInt(parts[i]);if(ip[i]<0||ip[i]>255)return false;}
        }catch(Exception e){return false;}
        return ip[0]==10 || ip[0]==127 || ip[0]==192&&ip[1]==168 ||
                ip[0]==172&&ip[1]>=16&&ip[1]<=31 || ip[0]==169&&ip[1]==254;
    }

    private static URL endpoint(String base) throws Exception {
        if(base==null||base.isEmpty())throw new Exception("请填写 API Base URL");
        URL u=new URL(base);
        String scheme=u.getProtocol().toLowerCase(Locale.ROOT);
        boolean tls="https".equals(scheme);
        if(!tls && (!"http".equals(scheme)||!privateHost(u.getHost())))
            throw new Exception("公网 API 必须使用 HTTPS；HTTP 仅支持 localhost 或局域网 IP");
        if(u.getUserInfo()!=null||u.getRef()!=null||u.getQuery()!=null||u.getHost().isEmpty())
            throw new Exception("API 地址不能包含账号密码、参数或锚点");
        String path=u.getPath();
        if(path==null)path="";
        path=path.replaceAll("/+$","");
        if(!path.endsWith("/chat/completions"))path+="/chat/completions";
        return new URL(scheme,u.getHost(),u.getPort(),path);
    }

    private synchronized String currentKey() throws Exception {
        String encoded=prefs.getString(SECRET,"");
        return encoded.isEmpty()?"":decrypt(encoded);
    }

    private static SecretKey getKey() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if(ks.containsAlias(KEY_ALIAS))
            return ((KeyStore.SecretKeyEntry)ks.getEntry(KEY_ALIAS,null)).getSecretKey();
        KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());
        return kg.generateKey();
    }

    private static String encrypt(String raw) throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE,getKey());
        byte[] iv=c.getIV();
        byte[] encrypted=c.doFinal(raw.getBytes(StandardCharsets.UTF_8));
        byte[] out=new byte[iv.length+encrypted.length];
        System.arraycopy(iv,0,out,0,iv.length);
        System.arraycopy(encrypted,0,out,iv.length,encrypted.length);
        return Base64.encodeToString(out,Base64.NO_WRAP);
    }

    private static String decrypt(String encoded) throws Exception {
        byte[] payload=Base64.decode(encoded,Base64.NO_WRAP);
        if(payload.length<29)throw new Exception("API Key 存储异常");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE,getKey(),new GCMParameterSpec(128,payload,0,12));
        return new String(c.doFinal(payload,12,payload.length-12),StandardCharsets.UTF_8);
    }

    private static String readLimited(InputStream stream) throws Exception {
        if(stream==null)return "";
        try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buf=new byte[8192];int count;int max=1024*1024;
            while((count=in.read(buf))!=-1) {
                if(out.size()+count>max)throw new Exception("API 响应超过 1MB，已停止读取");
                out.write(buf,0,count);
            }
            return out.toString("UTF-8");
        }
    }

    private static String messageText(Object content) {
        if(content instanceof String)return (String)content;
        if(content instanceof JSONArray) {
            JSONArray a=(JSONArray)content;StringBuilder result=new StringBuilder();
            for(int i=0;i<a.length();i++){
                JSONObject b=a.optJSONObject(i);
                if(b!=null && "text".equals(b.optString("type")))
                    result.append(b.optString("text"));
            }
            return result.toString();
        }
        return content==null?"":String.valueOf(content);
    }

    public String complete(JSONArray messages,int maxTokens) throws Exception {
        String base,model,key;
        synchronized(this) {
            base=prefs.getString(BASE,"");
            model=prefs.getString(MODEL,"");
            key=currentKey();
        }
        if(base.isEmpty()||model.isEmpty())
            throw new Exception("请到设置页面填写 API Base URL 和 Model，并保存配置");
        URL url=endpoint(base);
        JSONObject payload=new JSONObject();
        put(payload,"model",model);
        put(payload,"messages",messages);
        put(payload,"stream",false);
        put(payload,"max_tokens",maxTokens);
        HttpURLConnection conn=(HttpURLConnection)url.openConnection();
        conn.setInstanceFollowRedirects(false); // never forward authorization to another host
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(90000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
        conn.setRequestProperty("Accept","application/json");
        if(!key.isEmpty())conn.setRequestProperty("Authorization","Bearer "+key);
        try {
            try(OutputStream out=conn.getOutputStream()) {
                out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status=conn.getResponseCode();
            String response=readLimited(status>=400?conn.getErrorStream():conn.getInputStream());
            if(status<200||status>=300)
                throw new Exception("API HTTP "+status+": "+response.substring(0,Math.min(280,response.length())));
            JSONObject body=new JSONObject(response);
            JSONArray choices=body.optJSONArray("choices");
            if(choices==null||choices.length()==0)throw new Exception("API 未返回 choices（请使用 OpenAI Chat Completions 兼容接口）");
            JSONObject first=choices.optJSONObject(0);
            JSONObject message=first==null?null:first.optJSONObject("message");
            if(message==null)throw new Exception("API 未返回 choices[0].message");
            String content=messageText(message.opt("content")).trim();
            if(content.isEmpty())throw new Exception("API 返回内容为空，可能需要调整模型或最大输出长度");
            return content;
        } finally {conn.disconnect();}
    }

    public JSONObject test() {
        JSONArray messages=new JSONArray();
        messages.put(put(put(new JSONObject(),"role","user"),"content","只回复 OK"));
        JSONObject result=new JSONObject();
        try{
            String reply=complete(messages,24);
            put(result,"ok",true);
            put(result,"reply",reply.substring(0,Math.min(180,reply.length())));
        }catch(Exception e){
            put(result,"ok",false);
            put(result,"error",e.getMessage()==null?"未知接口错误":e.getMessage());
        }
        return result;
    }
}
