#!/usr/bin/env python3
"""Convert the v2 generated on-device HTML to configurable API + offline fallback UI."""
from pathlib import Path
import re
import subprocess

root=Path(__file__).resolve().parents[1]
p=root/"android-app/app/src/main/assets/index.html"
html=p.read_text(encoding="utf-8")

start=html.index('  <section class="page" id="page-settings">')
end=html.index('  </section>',start)+len('  </section>')
settings="""  <section class="page" id="page-settings">
    <div class="settings-wrap">
      <div class="card">
        <h3>🧠 通用大模型 API</h3>
        <p class="hint" style="line-height:1.8;margin-bottom:14px">兼容 OpenAI Chat Completions 格式，可连接 OpenAI、DeepSeek、通义千问、OpenRouter、Ollama 等兼容服务，模型及接口均由你自己配置。</p>
        <div class="form-row">
          <label>AI 模式</label>
          <select id="setMode" style="width:100%;height:44px;border-radius:9px;border:1px solid var(--line);padding:8px">
            <option value="api">通用大模型 API（默认）</option>
            <option value="local">本地规则分析（无网络也能使用）</option>
          </select>
        </div>
        <div class="form-row">
          <label>Base URL</label>
          <input id="setBaseurl" type="url" autocomplete="off" placeholder="https://api.example.com/v1">
          <div class="hint">填写 /v1 或完整 /chat/completions 地址，公网需 HTTPS。</div>
        </div>
        <div class="form-row">
          <label>Model（模型 ID）</label>
          <input id="setModel" autocomplete="off" placeholder="例如 deepseek-chat 或 qwen-plus">
        </div>
        <div class="form-row">
          <label>API Key</label>
          <input id="setApikey" type="password" autocomplete="new-password" placeholder="输入密钥（保存后不回显）">
          <div class="hint" id="apiKeyHint">密钥加密保存在本机，留空可保留旧密钥。</div>
          <label class="chk" style="margin-top:8px"><input id="clearApiKey" type="checkbox"> 清除已保存密钥</label>
        </div>
        <div class="form-actions" style="flex-wrap:wrap">
          <button class="btn btn-primary" onclick="saveLlm()">保存配置</button>
          <button class="btn btn-ghost" onclick="testLlm()">测试连接</button>
        </div>
        <div class="test-result" id="testResult"></div>
        <p class="hint" style="margin-top:14px;line-height:1.8">隐私：启用在线 AI 后，你发送的内容、最近的对话和筛选后的店铺/商品统计会提供给你选择的 API 服务商。数据库文件、联系方式和 API Key 不会作为提问内容发送；API Key 仅用于请求鉴权。</p>
      </div>
      <div class="card">
        <h3>📦 离线数据库</h3>
        <p style="font-size:13px;line-height:1.8">图表、筛选、商品与店铺查询、详情仍然在手机本地运行，离线也能使用。断网时可手动切换本地规则分析。</p>
        <div id="offlineCounts" class="hint"></div>
        <div class="hint">历史数据快照来自原项目上游 v1.0.0，不是实时库存。</div>
      </div>
      <div class="card">
        <h3>📂 聊天记录备份</h3>
        <div class="form-actions" style="flex-wrap:wrap">
          <button class="btn btn-primary" onclick="exportConfig()">分享聊天备份</button>
          <label class="btn btn-ghost" style="cursor:pointer">导入聊天备份<input type="file" accept="application/json,.json" onchange="importConfig(this)" style="display:none"></label>
          <button class="btn btn-ghost" onclick="resetLocalChats()">清空全部对话</button>
        </div>
        <div class="hint">备份不包含 API Key。</div>
        <div class="test-result" id="ioResult"></div>
      </div>
      <div class="card">
        <h3>ℹ️ 关于</h3>
        <p style="font-size:13px">链动小铺数据洞察 Android v3.0.0<br>本地数据 + 用户自选 AI API<br>仓库：qwer654/wzyp-view</p>
      </div>
    </div>
  </section>"""
html=html[:start]+settings+html[end:]

api_js=r"""
<script>
/* User-configurable model interface; native bridge is the only network-enabled component. */
const __aiPending = new Map();
let __aiSeq = 0;
window.__wzypApiDone = (id, raw) => {
  const t=__aiPending.get(String(id));
  if(!t)return;
  __aiPending.delete(String(id));
  try {
    const result=JSON.parse(raw);
    if(result && result._error)t.reject(new Error(result._error));
    else t.resolve(result);
  }catch(e){t.reject(e);}
};
function __aiCall(path,body) {
  return new Promise((resolve,reject)=>{
    const id=String(++__aiSeq);
    __aiPending.set(id,{resolve,reject});
    try {window.WzypAI.async(path,JSON.stringify(body||{}),id);}
    catch(e){__aiPending.delete(id);reject(e);}
  });
}
const __apiBase=api;
api=async (path,body)=>{
  if(path==='/api/llm/test')return __aiCall(path,body);
  if(path==='/api/chat'){
    const cfg=JSON.parse(window.WzypAI.settings());
    if(cfg.mode!=='local')return __aiCall(path,body);
  }
  return __apiBase(path,body);
};
refreshLlmBadge=async ()=>{
  const s=JSON.parse(window.WzypAI.settings());
  const b=$('#llmBadge');
  const local=s.mode==='local';
  b.className='llm-badge'+(!local && !(s.baseurl && s.model)?' off':'');
  b.textContent=local?'本地规则分析':s.baseurl&&s.model?'自定义 AI API':'API 未配置';
};
llmReady=async ()=>{
  const s=JSON.parse(window.WzypAI.settings());
  if(s.mode==='local' || s.baseurl && s.model)return true;
  toast('请先在设置页配置 Base URL 和 Model');
  document.querySelector('[data-page=settings]').click();
  return false;
};
const __loadOldSettings=loadSettings;
loadSettings=async ()=>{
  await __loadOldSettings();
  const s=JSON.parse(window.WzypAI.settings());
  $('#setMode').value=s.mode||'api';
  $('#setBaseurl').value=s.baseurl||'';
  $('#setModel').value=s.model||'';
  $('#setApikey').value='';
  $('#clearApiKey').checked=false;
  $('#apiKeyHint').textContent=s.has_key?'API Key 已加密保存在手机，留空不修改。':'尚未保存 Key，可以填写，也可以用于无 Key 的局域网 API。';
};
async function saveLlm(){
  const r=JSON.parse(window.WzypAI.save(JSON.stringify({
    mode:$('#setMode').value,
    baseurl:$('#setBaseurl').value.trim(),
    model:$('#setModel').value.trim(),
    apikey:$('#setApikey').value.trim(),
    clear_key:$('#clearApiKey').checked
  })));
  const el=$('#testResult');
  if(r._error){el.className='test-result bad';el.textContent='保存失败：'+r._error;return false;}
  el.className='test-result ok';el.textContent='配置已保存到本机。';
  await loadSettings();
  await refreshLlmBadge();
  return true;
}
async function testLlm(){
  if(!await saveLlm())return;
  const el=$('#testResult');
  if($('#setMode').value==='local'){
    el.className='test-result ok';el.textContent='本地规则模式无需 API 连接。';return;
  }
  el.className='test-result ok';el.textContent='正在测试连接…';
  try {
    const r=await api('/api/llm/test');
    el.className='test-result '+(r.ok?'ok':'bad');
    el.textContent=r.ok?'连接成功，模型回复：'+r.reply:'连接失败：'+(r.error||'未知错误');
  }catch(e){el.className='test-result bad';el.textContent='连接失败：'+e.message;}
}
loadSettings().catch(e=>console.error(e));
refreshLlmBadge();
</script>
"""
html=html.replace("</body>",api_js+"</body>")
# Make missing API configuration discoverable before the next message.
html=html.replace("  if(!message || sending)return;","  if(!message || sending)return;\n  if(!(await llmReady()))return;")
html=html.replace("bubble.textContent='本地分析失败：'+e.message","bubble.textContent='AI 请求失败：'+e.message")
html=html.replace("const bubble=appendMsg('ai','正在查询本地数据库…');",
                  "const bubble=appendMsg('ai','正在生成分析…');")

# The original "AI 解读" context included QQ contacts. Never implicitly send that field.
html=html.replace("均价:r.avg_price,QQ:r.contact_qq", "均价:r.avg_price")
# Markdown from user-selected APIs is untrusted. Restrict HTML elements and URL schemes
# before inserting generated content into the application DOM.
html=html.replace(
    "return html; // marked 默认对 html 标签转义输出",
    """const sandbox=document.createElement('template');
    sandbox.innerHTML=html;
    const allowed=new Set(['P','BR','STRONG','B','EM','I','S','DEL','H1','H2','H3',
      'H4','H5','H6','PRE','CODE','BLOCKQUOTE','UL','OL','LI','TABLE',
      'THEAD','TBODY','TR','TH','TD','HR','A']);
    sandbox.content.querySelectorAll('*').forEach(node=>{
      if(!allowed.has(node.tagName)){
        node.replaceWith(document.createTextNode(node.textContent||''));
        return;
      }
      for(const attr of Array.from(node.attributes)){
        if(node.tagName==='A' && attr.name==='href')continue;
        node.removeAttribute(attr.name);
      }
      if(node.tagName==='A'){
        const link=node.getAttribute('href')||'';
        if(!/^https?:\\/\\//i.test(link))node.removeAttribute('href');
        else {node.setAttribute('target','_blank');node.setAttribute('rel','noopener noreferrer');}
      }
    });
    return sandbox.innerHTML;"""
)

p.write_text(html,encoding="utf-8")
for idx,js in enumerate(re.findall(r'<script[^>]*>(.*?)</script>',html,re.S|re.I)):
    if not js.strip():continue
    tmp=p.parent/f"api-script-{idx}.js"
    try:
        tmp.write_text(js,encoding="utf-8")
        subprocess.run(["node","--check",str(tmp)],check=True)
    finally:
        tmp.unlink(missing_ok=True)
assert "WzypAI.async" in html and 'id="setApikey"' in html
print("API-enabled UI packaged; HTML bytes:",p.stat().st_size)
