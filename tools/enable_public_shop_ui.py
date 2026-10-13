#!/usr/bin/env python3
"""Add a first-party public-storefront refresh interface to the Android settings page."""
from pathlib import Path
import re
import subprocess

root=Path(__file__).resolve().parents[1]
file=root/"android-app/app/src/main/assets/index.html"
html=file.read_text(encoding="utf-8")
needle='      <div class="card">\n        <h3>🔁 增量同步 · 自动检查</h3>'
if needle not in html:
    raise SystemExit("Missing v5 data settings anchor")
html=html.replace(needle, """      <div class="card">
        <h3>🌐 官方公开店铺 · 直接刷新</h3>
        <p style="font-size:13px;line-height:1.8">
          <b>无需自建数据库服务器：</b>尝试从链动小铺公开店铺商品列表接口，
          直接更新本机数据库里<b>已有店铺</b>的商品名、价格与上架状态。
          同店最多两个分类并行读取、全局间隔至少 1 秒；一次最多刷新 8 家，自动保存进度。失败保留旧数据。
        </p>
        <div id="publicShopInfo" class="hint" style="line-height:1.8;white-space:pre-line;margin:8px 0"></div>
        <div id="publicShopHealth" class="hint" style="line-height:1.8;white-space:pre-line;margin:8px 0"></div>
        <div id="publicJobsHealth" class="hint" style="line-height:1.8;white-space:pre-line;margin:8px 0"></div>
        <div class="form-row">
          <label>公开站点域名（手动选择，不自动绕过平台验证）</label>
          <select id="publicShopHost" style="width:100%;padding:10px;border-radius:8px;border:1px solid var(--line)">
            <option value="wzyp.cn">wzyp.cn（默认）</option>
            <option value="pay.ldxp.cn">pay.ldxp.cn（公开旧站）</option>
          </select>
        </div>
        <div class="form-row">
          <label>指定店铺 Token / 店铺 URL（可选）</label>
          <input id="publicShopTarget" autocomplete="off" spellcheck="false"
             placeholder="留空按顺序刷新；或 https://wzyp.cn/shop/xxxx">
          <div class="hint">输入指定店铺时只刷新该店。支持手动指定未收录的店铺：公开接口返回商品后自动添加到本地库。</div>
        </div>
        <div class="form-row">
          <label style="display:flex;align-items:center;gap:8px;cursor:pointer">
            <input id="publicShopAuto" type="checkbox" style="width:auto">
            开启每日 Wi-Fi 自动更新（一次最多 4 家已有店铺）
          </label>
          <p class="hint">Android 省电机制可能推迟后台更新。没有加入自动购卡、登录态采集或反验证机制。</p>
        </div>
        <div class="form-actions" style="flex-wrap:wrap">
          <button class="btn btn-primary" onclick="refreshPublicShops(4)" id="publicShopRefreshBtn">立即刷新下一批 4 家</button>
          <button class="btn btn-ghost" onclick="refreshPublicShops(8)" id="publicShopRefresh8">刷新下一批 8 家</button>
          <button class="btn btn-ghost" onclick="cancelPublicShops()" id="publicShopCancel" disabled>取消本次刷新</button>
          <button class="btn btn-ghost" onclick="savePublicAuto()">保存自动更新设置</button>
          <button class="btn btn-ghost" onclick="skipPublicShop()">手动跳过当前店铺</button>
        </div>
        <div class="hint" style="margin-top:10px">性能优化：最多 2 路请求同时在途，全局每秒至多开始 1 个请求；一次性对比同店旧商品，跳过无变化的数据库写入。</div>
        <div id="publicShopProgress" class="test-result" style="line-height:1.8;white-space:pre-line"></div>
        <p class="hint" style="line-height:1.8">公开商品源如返回 HTML/验证页，系统暂缓后台重试 12 小时，避免反复消耗流量。可手动选择其他公开域名后重试，或改用下方 SQLite/JSON 数据源；自动跳过验证不被支持。新店铺可通过粘贴链接添加，新商品可同步；未获取到的旧商品不会被误删。商品详情全文及新店铺发现需要单独接口。</p>
      </div>
""" + needle,1)
html=html.replace("v5.0.0","v8.0.3").replace("v6.0.0","v8.0.3").replace("v7.0.0","v8.0.3").replace("v8.0.2","v8.0.3")
script=r"""
<script>
function renderPublicInfo(){
  try{
    const info=JSON.parse(window.WzypData.publicShopSettings());
    $('#publicShopAuto').checked=!!info.auto;
    $('#publicShopHost').value=info.host||'wzyp.cn';
    const date=info.last_success?new Date(info.last_success).toLocaleString('zh-CN'):'无';
    $('#publicShopInfo').textContent=
      '下次轮到店铺序号：'+(Number(info.offset||0)+1)+
      '\n上次成功：'+date+
      '\n最近结果：'+(info.last_result||'无');
    const cooldown=Number(info.hold_until_ms||0);
    const status=$('#publicShopHealth');
    const lastHttp=Number(info.last_http_status||0);
    const lastWhen=info.last_checked_ms ? new Date(info.last_checked_ms).toLocaleString('zh-CN') : '未检测';
    status.textContent='接口诊断：'+(info.last_host||info.host||'wzyp.cn')+
      ' · HTTP '+(lastHttp||'未检测')+
      ' · '+(info.last_content_type||'未知内容类型')+
      '\n上次接口检测：'+lastWhen+
      (cooldown>Date.now() ? '\n后台已暂停重试至 '+new Date(cooldown).toLocaleString('zh-CN')+
        '（不影响手动检查及离线查询）':'');
    status.style.color=info.last_error_kind ? '#b45309' : 'var(--sub)';
    const jobs=JSON.parse(window.WzypData.syncHealth());
    const statusText=b=>b?'已注册':'未注册';
    $('#publicJobsHealth').textContent=
      '系统网络权限：'+(jobs.network_permission?'正常':'缺失')+
      ' · 每日店铺更新任务：'+statusText(jobs.public_scheduled)+
      ' · 增量更新任务：'+statusText(jobs.delta_scheduled)+
      '\n注：未开启的自动任务显示“未注册”是正常现象。';
  }catch(e){
    $('#publicShopInfo').textContent='读取公开刷新状态失败：'+e.message;
  }
}
function skipPublicShop(){
  if(!confirm('只跳过当前轮到的一个店铺，保留该店所有历史记录，确认？'))return;
  const state=JSON.parse(window.WzypData.publicShopSkip());
  const box=$('#publicShopProgress');
  box.className='test-result '+(state._error?'bad':'ok');
  box.textContent=state._error||('已跳过店铺 '+state.skipped+'，下次将继续检查下一家。');
  renderPublicInfo();
}
function savePublicAuto(){
  const hostResult=JSON.parse(window.WzypData.publicShopSetHost($('#publicShopHost').value));
  if(hostResult._error){
    $('#publicShopProgress').textContent=hostResult._error;
    return false;
  }
  const state=JSON.parse(window.WzypData.publicShopSetAuto($('#publicShopAuto').checked));
  const node=$('#publicShopProgress');
  if(state._error){
    node.className='test-result bad';
    node.textContent='保存失败：'+state._error;
    return false;
  }
  node.className='test-result ok';
  node.textContent='每日 Wi-Fi 自动更新设置已保存。';
  renderPublicInfo();
  return true;
}
let __publicShopChanged=0;
function setPublicBusy(busy){
  ['publicShopRefreshBtn','publicShopRefresh8'].forEach(id=>$('#'+id).disabled=busy);
  $('#publicShopCancel').disabled=!busy;
}
function cancelPublicShops(){
  window.WzypData.publicShopCancel();
  $('#publicShopProgress').textContent='正在取消刷新并停止当前网络请求…';
}
function refreshPublicShops(limit){
  if(!savePublicAuto())return;
  const node=$('#publicShopProgress');
  node.className='test-result ok';
  node.textContent='正在请求公开店铺商品列表…';
  setPublicBusy(true);
  __publicShopChanged=0;
  const target=$('#publicShopTarget').value.trim();
  window.WzypData.publicShopRefresh(target,limit);
}
const _v5DbEvent=window.__wzypDbUpdate;
window.__wzypDbUpdate=async message=>{
  if(message.stage && message.stage.startsWith('public_')){
    const node=$('#publicShopProgress');
    node.className='test-result '+(message.stage==='public_error'?'bad':'ok');
    node.textContent=message.message||'';
    if(message.stage==='public_shop_done'){
      __publicShopChanged++;
      renderPublicInfo();
      if(message.details)
        node.textContent+=(message.details.added!==undefined?
           '\n新增 '+message.details.added+' / 修改 '+message.details.modified+
           ' / 无变化 '+message.details.unchanged+
           ' / 用时 '+(Number(message.details.elapsed_ms||0)/1000).toFixed(1)+' 秒':'');
    }
    if(message.stage==='public_done'||message.stage==='public_error'){
      setPublicBusy(false);
      renderPublicInfo();
      if(message.stage==='public_done' && message.details){
        const d=message.details;
        const count=d.shops===undefined?1:d.shops;
        node.textContent='完成 '+count+' 家 / '+(d.requests||0)+' 次接口请求 / 用时 '+
          (Number(d.elapsed_ms||0)/1000).toFixed(1)+' 秒；新增 '+d.added+'、修改 '+d.modified;
      }
      if(message.stage==='public_done' && _v5DbEvent)
        await _v5DbEvent({stage:'done',percent:100,message:'公开店铺商品已刷新',details:message.details||null});
      if(message.stage==='public_error' && __publicShopChanged>0){
        // Previous shops were committed before a later shop failed. Refresh only
        // the visible dashboard; never falsely report the entire batch succeeded.
        try{await loadDashboard();}catch(e){}
      }
      __publicShopChanged=0;
    }
    return;
  }
  if(_v5DbEvent)await _v5DbEvent(message);
};
renderPublicInfo();
</script>
"""
html=html.replace("</body>",script+"</body>")
file.write_text(html,encoding="utf-8")
for i,inline in enumerate(re.findall(r'<script[^>]*>(.*?)</script>',html,re.S|re.I)):
    if inline.strip():
        temp=file.parent/f"public-script-{i}.js"
        try:
            temp.write_text(inline,encoding="utf-8")
            subprocess.run(["node","--check",str(temp)],check=True)
        finally:temp.unlink(missing_ok=True)
assert html.count("function refreshPublicShops(")==1
print("Public-storefront UI bundled:",file.stat().st_size)
