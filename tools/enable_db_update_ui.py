#!/usr/bin/env python3
"""Add SQLite snapshot update controls to generated Android v3 HTML without editing server UI."""
from pathlib import Path
import re
import subprocess

root=Path(__file__).resolve().parents[1]
p=root/"android-app/app/src/main/assets/index.html"
html=p.read_text(encoding="utf-8")
anchor='      <div class="card">\n        <h3>📂 聊天记录备份</h3>'
if anchor not in html: raise RuntimeError("Missing data page position")
panel="""      <div class="card">
        <h3>🔄 更新店铺和商品数据库</h3>
        <p style="font-size:13px;line-height:1.8">数据默认来自 APK 的历史快照。想查看新店铺和商品，需要导入你自己的最新 SQLite 数据，或者从一个返回 SQLite 文件的 HTTPS 地址下载。这里只负责更新数据，<b>不会自动抓取原网站</b>。</p>
        <div id="snapshotInfo" style="font-size:13px;margin:10px 0;line-height:1.8;color:var(--sub)"></div>
        <div class="form-actions" style="flex-wrap:wrap;margin-bottom:12px">
          <button class="btn btn-primary" id="pickDatabase" onclick="window.WzypData.chooseFile()">从手机导入 SQLite</button>
          <button class="btn btn-ghost" id="restoreDatabase" onclick="restoreDatabase()">恢复 APK 内置数据</button>
        </div>
        <div class="form-row">
          <label>远程数据库文件地址（HTTPS）</label>
          <input id="snapshotUrl" type="url" placeholder="https://your-server.example.com/wzyp.db" autocomplete="off">
          <div class="hint">地址必须提供原始 SQLite .db/.sqlite 文件，不能填写网页、MySQL 地址或普通 API JSON。下载到手机本地后离线使用。</div>
        </div>
        <div class="form-actions">
          <button class="btn btn-primary" id="syncDatabase" onclick="downloadDatabase()">下载并更新数据库</button>
        </div>
        <div id="snapshotResult" class="test-result" style="line-height:1.7"></div>
        <p class="hint" style="line-height:1.7">更新前会检查文件完整性、店铺/商品/商品详情表及必要字段；不合格不会替换旧库。更新保留本地聊天和 API 设置。文件最大 600 MB，建议使用稳定 Wi-Fi。</p>
      </div>
"""
html=html.replace(anchor,panel+anchor,1)
html=html.replace("Android v3.0.0","Android v4.0.0").replace("v3.0.0","v4.0.0")
new_script=r"""
<script>
/* SQLite import/download status and data invalidation after a successful swap. */
function refreshSnapshotInfo(){
  try{
    const d=JSON.parse(window.WzypOffline.request('/api/data/info','{}'));
    if(d._error)throw new Error(d._error);
    const source=d.source||'APK 内置数据';
    const when=d.updated_ms?new Date(d.updated_ms).toLocaleString('zh-CN'):'尚未导入更新';
    $('#snapshotInfo').textContent='当前：'+source+' · 店铺 '+Number(d.shops||0).toLocaleString()+
       ' / 商品 '+Number(d.goods||0).toLocaleString()+
       ' / 详情 '+Number(d.details||0).toLocaleString()+
       ' · 更新时间 '+when;
  }catch(e){$('#snapshotInfo').textContent='读取数据状态失败：'+e.message;}
}
window.__wzypDbUpdate=async d=>{
  const view=$('#snapshotResult');
  if(!view)return;
  const busy=['copying','validating'].includes(d.stage);
  ['pickDatabase','restoreDatabase','syncDatabase'].forEach(id=>{
    const btn=$('#'+id); if(btn)btn.disabled=busy;
  });
  view.className='test-result '+(d.stage==='error'?'bad':'ok');
  view.textContent=(d.percent>=0&&d.percent<=100?'['+d.percent+'%] ':'')+(d.message||'');
  if(d.stage==='done'){
    refreshSnapshotInfo();
    toast('数据库已更新，正在刷新数据');
    try {
      shopLoaded=false;
      goodsLoaded=false;
      await loadDashboard();
      await loadSettings();
      const shops=document.querySelector('#page-shops');
      const goods=document.querySelector('#page-goods');
      if(shops && shops.classList.contains('active'))await loadShops(1);
      if(goods && goods.classList.contains('active'))await initGoodsPage();
    }catch(e){toast('更新成功，但刷新失败；请重启应用');}
  }
};
function downloadDatabase(){
  const url=$('#snapshotUrl').value.trim();
  if(!url.startsWith('https://')){
    $('#snapshotResult').textContent='请填写直接返回 SQLite 数据的 HTTPS 地址';
    $('#snapshotResult').className='test-result bad';
    return;
  }
  window.__wzypDbUpdate({stage:'copying',message:'正在连接数据库下载地址…',percent:0});
  window.WzypData.download(url);
}
function restoreDatabase(){
  if(!confirm('将恢复安装包内置的历史数据快照，当前已导入的数据库将被替换。确认继续？'))return;
  window.__wzypDbUpdate({stage:'copying',message:'正在恢复内置数据库…',percent:0});
  window.WzypData.resetBundled();
}
refreshSnapshotInfo();
</script>
"""
html=html.replace('</body>',new_script+'</body>')
p.write_text(html,encoding="utf-8")
for i,script in enumerate(re.findall(r'<script[^>]*>(.*?)</script>',html,re.S|re.I)):
    if not script.strip():continue
    tmp=p.parent/f"sqlite-js-validate-{i}.js"
    try:
        tmp.write_text(script,encoding="utf-8")
        subprocess.run(["node","--check",str(tmp)],check=True)
    finally: tmp.unlink(missing_ok=True)
assert 'window.WzypData.chooseFile()' in html
assert 'window.__wzypDbUpdate' in html
print("SQLite update interface packaged and JS syntax verified:",p.stat().st_size)
