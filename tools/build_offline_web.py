#!/usr/bin/env python3
"""Package existing dashboard as 100% on-device WebView HTML for offline Android.
This intentionally leaves the original server-facing web app unchanged.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
web = root / "static" / "index.html"
assets = root / "android-app" / "app" / "src" / "main" / "assets"
assets.mkdir(parents=True, exist_ok=True)
html = web.read_text(encoding="utf-8")

def replace_block(start, end, replacement):
    global html
    a = html.index(start)
    b = html.index(end, a)
    html = html[:a] + replacement + "\n" + html[b:]

html = html.replace('src="/static/marked.min.js?v=1.2.0"', 'src="marked.min.js"')

replace_block("async function api(path,body){", "function esc(s){", """
async function api(path,body){
  const response = window.WzypOffline.request(path, JSON.stringify(body || {}));
  const obj = JSON.parse(response);
  if(obj && obj._error) throw new Error(obj._error);
  return obj;
}
""")

# The server settings are not meaningful for an on-device database / deterministic analyzer.
replace_block('  <section class="page" id="page-settings">', '  </section>', """
  <section class="page" id="page-settings">
    <div class="settings-wrap">
      <div class="card">
        <h3>📦 手机内置离线数据库</h3>
        <p style="font-size:14px;line-height:1.9">店铺、商品与详情快照已随 APK 安装，所有查询都在手机本地 SQLite 执行，无需服务器、账号、MySQL 或网络权限。</p>
        <p style="font-size:13px;line-height:1.9;color:var(--sub)">数据来源：Samge0/wzyp-view v1.0.0 历史快照；不自动更新。原商品链接只作为引用，离线时无法打开外部网站。</p>
        <div id="offlineCounts" style="font-size:13px;margin-top:12px"></div>
      </div>
      <div class="card">
        <h3>🧠 本地分析引擎</h3>
        <p style="font-size:13px;line-height:1.9">支持店铺/商品数量、价格分布、销量排名、指定商品类型、关键字查询与结果解读，离线直接统计真实数据库。</p>
        <p style="font-size:13px;color:#a16207;margin-top:8px">注意：规则型本地分析不是大语言模型；开放式问答、复杂跨轮推理与生成能力比原 LangGraph AI 有所减少。</p>
      </div>
      <div class="card">
        <h3>⚙️ 本地设置 / 数据备份</h3>
        <div class="form-actions" style="flex-wrap:wrap">
          <button class="btn btn-primary" onclick="exportConfig()">分享配置备份</button>
          <label class="btn btn-ghost" style="cursor:pointer">导入配置备份<input type="file" accept="application/json,.json" onchange="importConfig(this)" style="display:none"></label>
          <button class="btn btn-ghost" onclick="resetLocalChats()">清空全部本地对话</button>
        </div>
        <div class="test-result" id="ioResult"></div>
      </div>
      <div class="card">
        <h3>ℹ️ 关于</h3>
        <p style="font-size:13px;line-height:1.8">链动小铺数据洞察 · 离线 Android v2.0.0<br>源码：qwer654/wzyp-view</p>
      </div>
    </div>
  </section>
""")
# Remove first duplicate closing section produced by replace_block.
import re
html = re.sub(r'(</section>)\s*</section>(?=\s*</main>)', r'\1', html, count=1)

replace_block("async function sendChat(){", "function buildFollowupsEl(fs){", """
async function sendChat(){
  const message=$('#chatInput').value.trim();
  if(!message || sending)return;
  sending=true;
  $('#sendBtn').disabled=true;
  $('#chatInput').value='';
  $('#chatInput').style.height='auto';
  appendMsg('user',message);
  const bubble=appendMsg('ai','正在查询本地数据库…');
  try {
    const answer=await api('/api/chat',{thread_id:threadId,message});
    bubble.innerHTML='<div class="md">'+mdRender(answer.reply||'未找到相关结果')+'</div>'+
      (answer.followups && answer.followups.length ? renderFollowups(answer.followups) : '');
  }catch(e){
    bubble.textContent='本地分析失败：'+e.message;
  }
  const t=threads.find(x=>x.id===threadId);
  if(t&&t.title==='新对话'){
    t.title=message.slice(0,16);
    localStorage.setItem(THREADS_KEY,JSON.stringify(threads));renderThreads();
  }
  sending=false;
  $('#sendBtn').disabled=false;
  scrollChat();
}
""")
replace_block("async function llmReady(){", "/* ================= AI 解读（查询上下文 → 聊天） ================= */", """
async function llmReady(){ return true; }
async function refreshLlmBadge(){
  const b=$('#llmBadge');
  b.className='llm-badge';
  b.textContent='已离线 · 本地分析';
}
""")
replace_block("async function loadSettings(){", "/* ================= 启动 ================= */", """
async function loadSettings(){
  const s=await api('/api/stats');
  const el=$('#offlineCounts');
  if(el) el.textContent='已内置 '+Number(s.stats.shop_count).toLocaleString('zh-CN')+
    ' 家店铺、'+Number(s.stats.goods_count).toLocaleString('zh-CN')+
    ' 件商品、'+Number(s.stats.detail_count).toLocaleString('zh-CN')+' 条商品详情';
}
function exportConfig(){
  const payload=JSON.stringify({
    format:'wzyp-android-offline-v2', export_time:new Date().toISOString(),
    threads:localStorage.getItem(THREADS_KEY) || '[]',
    backups:threads.map(t=>({id:t.id,history:JSON.parse(window.WzypOffline.request(
       '/api/chat/history?thread_id='+encodeURIComponent(t.id),'{}')).messages || []}))
  },null,2);
  window.WzypOffline.shareText(payload);
  toast('已打开 Android 分享选择器');
}
async function importConfig(input){
  const f=input.files && input.files[0];
  if(!f)return;
  const el=$('#ioResult');
  try{
    const d=JSON.parse(await f.text());
    if(d.format!=='wzyp-android-offline-v2'||!Array.isArray(d.backups))
       throw new Error('不是兼容的离线备份');
    const safeThreads=JSON.parse(d.threads||'[]');
    if(!Array.isArray(safeThreads)||safeThreads.length>100)throw new Error('会话清单异常');
    for(const item of d.backups){
      if(typeof item.id!=='string'||!Array.isArray(item.history))continue;
      window.WzypOffline.request('/api/chat/restore',JSON.stringify({
        thread_id:item.id,messages:item.history.slice(-100)
      }));
    }
    threads=safeThreads.slice(0,20);
    localStorage.setItem(THREADS_KEY,JSON.stringify(threads));
    renderThreads();
    if(threads.length){threadId=threads[0].id;loadHistory();}
    el.className='test-result ok';
    el.textContent='✅ 本地会话恢复成功';
  }catch(e){el.className='test-result bad';el.textContent='❌ 导入失败：'+e.message;}
  input.value='';
}
function resetLocalChats(){
  if(!confirm('确认清空全部本地聊天历史？'))return;
  for(const t of threads)window.WzypOffline.request('/api/chat/delete',JSON.stringify({thread_id:t.id}));
  localStorage.removeItem(THREADS_KEY);
  threads=[];
  threadId='t-'+Date.now().toString(36);
  threads=[{id:threadId,title:'新对话'}];
  localStorage.setItem(THREADS_KEY,JSON.stringify(threads));
  renderThreads();renderMessages([]);toast('已清空聊天历史');
}
""")

# block local network resource loads in WebView; even external links remain browser-only.
html=html.replace('href="/static/favicon.svg"','href="favicon.svg"')
html=html.replace('<title>wzyp 店铺数据洞察</title>', '<title>wzyp 离线数据洞察</title>')
html=html.replace('wzyp 店铺数据洞察</div>', 'wzyp 离线数据洞察</div>')
html=html.replace('LLM 未连接</div>', '本地离线</div>')
(assets / "index.html").write_text(html, encoding="utf-8")
for name in ("marked.min.js", "favicon.svg"):
    (assets / name).write_bytes((root / "static" / name).read_bytes())

assert 'WzypOffline.request' in html
assert 'src="marked.min.js"' in html
assert 'id="page-settings"' in html
original_sections=(root / "static" / "index.html").read_text(encoding="utf-8").count('</section>')
generated_sections=html.count('</section>')
assert generated_sections == original_sections, f"unbalanced sections: generated={generated_sections}, original={original_sections}"
print("Offline HTML packaged", len(html), "bytes")
