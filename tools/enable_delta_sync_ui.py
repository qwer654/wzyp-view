#!/usr/bin/env python3
"""Add a user-owned incremental JSON feed, manual sync and optional periodic job UI."""
from pathlib import Path
import re
import subprocess

root=Path(__file__).resolve().parents[1]
file=root/"android-app/app/src/main/assets/index.html"
html=file.read_text(encoding="utf-8")
before='      <div class="card">\n        <h3>📂 聊天记录备份</h3>'
if before not in html: raise SystemExit("DB panel not generated before sync panel")

card="""      <div class="card">
        <h3>🔁 增量同步 · 自动检查</h3>
        <p style="font-size:13px;line-height:1.8">配置由你管理的、提供 <b>wzyp JSON 增量数据</b> 的 HTTPS 地址。同步时只传输新增、修改或删除的记录，应用在本地 SQLite 事务中更新，失败不会留下半份数据。</p>
        <div class="form-row">
          <label>增量 JSON 数据源 URL（HTTPS）</label>
          <input id="deltaUrl" type="url" autocomplete="off" spellcheck="false" placeholder="https://example.com/feeds/wzyp-delta.json">
          <div class="hint">不是大模型 API 地址，也不是原平台首页。响应必须遵循仓库的增量 JSON 协议。</div>
        </div>
        <div class="form-row">
          <label style="display:flex;align-items:center;gap:8px;cursor:pointer">
            <input type="checkbox" id="deltaAuto" style="width:auto">
            开启后台自动检查（约每 12 小时，系统可能延迟）
          </label>
        </div>
        <div class="form-actions" style="flex-wrap:wrap">
          <button class="btn btn-primary" onclick="saveDeltaSettings()">保存同步源</button>
          <button class="btn btn-ghost" id="deltaSyncNow" onclick="syncDeltaNow()">立即检查并同步</button>
        </div>
        <div id="deltaStatus" class="test-result" style="line-height:1.8"></div>
        <p class="hint" style="margin-top:12px;line-height:1.8">首次从旧版数据库同步，数据源的 base_revision 应为空；以后的增量包需要衔接前一个 revision。不匹配时请先导入对应完整数据库。自动检查需要网络，不会调用 AI API，也不会登录或抓取原平台。</p>
      </div>
"""
html=html.replace(before,card+before,1)
html=html.replace("v4.0.0","v5.0.0")

script=r"""
<script>
function __readDeltaPrefs(){
  const config=JSON.parse(window.WzypData.deltaSettings());
  $('#deltaUrl').value=config.url||'';
  $('#deltaAuto').checked=!!config.enabled;
  const time=config.last_check ? new Date(config.last_check).toLocaleString('zh-CN') : '从未';
  const state=config.revision||'初始未编号快照';
  $('#deltaStatus').textContent='当前同步版本：'+state+
    '\n上次检查：'+time+'\n最近结果：'+(config.last_result||'暂无');
  $('#deltaStatus').style.whiteSpace='pre-line';
}
function saveDeltaSettings(){
  const response=JSON.parse(window.WzypData.saveDeltaSettings(JSON.stringify({
    url:$('#deltaUrl').value.trim(),enabled:$('#deltaAuto').checked
  })));
  if(response._error){
    $('#deltaStatus').className='test-result bad';
    $('#deltaStatus').textContent='保存失败：'+response._error;
    return false;
  }
  $('#deltaStatus').className='test-result ok';
  __readDeltaPrefs();
  toast('同步源已保存');
  return true;
}
function syncDeltaNow(){
  if(!saveDeltaSettings())return;
  if(!$('#deltaUrl').value.trim()) {
    $('#deltaStatus').className='test-result bad';
    $('#deltaStatus').textContent='请先填写增量数据源 URL';
    return;
  }
  $('#deltaSyncNow').disabled=true;
  $('#deltaStatus').className='test-result ok';
  $('#deltaStatus').textContent='正在连接增量数据源…';
  window.WzypData.syncDelta();
}
const __existingDbStatus=window.__wzypDbUpdate;
window.__wzypDbUpdate=async message=>{
  if(message.stage==='checking'||message.stage==='applying'){
    $('#deltaSyncNow').disabled=true;
    $('#deltaStatus').textContent=message.message||'检查中…';
    $('#deltaStatus').className='test-result ok';
    return;
  }
  if(['updated','unchanged'].includes(message.stage)){
    $('#deltaSyncNow').disabled=false;
    $('#deltaStatus').className='test-result ok';
    __readDeltaPrefs();
    if(message.stage==='updated' && __existingDbStatus) {
      await __existingDbStatus({stage:'done',percent:100,
        message:message.message,details:message.details||null});
    } else if(message.stage==='unchanged')toast('数据版本未变化');
    return;
  }
  if(message.stage==='error'){
    $('#deltaSyncNow').disabled=false;
    $('#deltaStatus').className='test-result bad';
    $('#deltaStatus').textContent=message.message||'增量同步失败';
  }
  if(__existingDbStatus)await __existingDbStatus(message);
};
__readDeltaPrefs();
</script>
"""
html=html.replace("</body>",script+"</body>")
file.write_text(html,encoding="utf-8")
for i,text in enumerate(re.findall(r'<script[^>]*>(.*?)</script>',html,re.S|re.I)):
    if not text.strip():continue
    js=file.parent/f"delta-validation-{i}.js"
    try:
        js.write_text(text,encoding="utf-8")
        subprocess.run(["node","--check",str(js)],check=True)
    finally:js.unlink(missing_ok=True)
print("v5 incremental sync UI ready:",file.stat().st_size)
