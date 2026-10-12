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
          一次最多刷新 8 家，自动保存下一个待更新位置。失败时保留旧数据。
        </p>
        <div id="publicShopInfo" class="hint" style="line-height:1.8;white-space:pre-line;margin:8px 0"></div>
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
          <button class="btn btn-ghost" onclick="refreshPublicShops(8)">刷新下一批 8 家</button>
          <button class="btn btn-ghost" onclick="savePublicAuto()">保存自动更新设置</button>
        </div>
        <div id="publicShopProgress" class="test-result" style="line-height:1.8;white-space:pre-line"></div>
        <p class="hint" style="line-height:1.8">接口若要求登录、验证码、返回 HTML 验证页面或限流，将提示并停止，不绕过验证。新店铺可通过粘贴链接添加，新商品可同步；未获取到的旧商品不会被误删。商品详情全文及新店铺发现需要单独接口。</p>
      </div>
""" + needle,1)
html=html.replace("v5.0.0","v6.0.0")
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
  }catch(e){
    $('#publicShopInfo').textContent='读取公开刷新状态失败：'+e.message;
  }
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
function refreshPublicShops(limit){
  if(!savePublicAuto())return;
  const node=$('#publicShopProgress');
  node.className='test-result ok';
  node.textContent='正在请求公开店铺商品列表…';
  $('#publicShopRefreshBtn').disabled=true;
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
      renderPublicInfo();
      if(message.details)
        node.textContent+=(message.details.added!==undefined?
           '\n新增 '+message.details.added+' / 修改 '+message.details.modified+
           ' / 无变化 '+message.details.unchanged:'');
    }
    if(message.stage==='public_done'||message.stage==='public_error'){
      $('#publicShopRefreshBtn').disabled=false;
      renderPublicInfo();
      if(message.stage==='public_done' && _v5DbEvent)
        await _v5DbEvent({stage:'done',percent:100,message:'公开店铺商品已刷新',details:message.details||null});
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
