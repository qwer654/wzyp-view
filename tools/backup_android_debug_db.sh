#!/usr/bin/env bash
# Backup the locally modified SQLite dataset from an installed CI-debug-signed app.
# Uses Android's run-as only when the installed APK is debuggable; NEVER removes the app.
set -euo pipefail
command -v adb >/dev/null || { echo "缺少 Android platform-tools (adb)" >&2; exit 1; }
command -v python3 >/dev/null || { echo "缺少 python3" >&2; exit 1; }
PACKAGE=cn.wzyp.view
OUT="${1:-wzyp-android-backup}"
mkdir -p "$OUT"
DB="$OUT/wzyp_snapshot_backup.db"
echo "检查设备及旧版 APK 调试权限..."
adb wait-for-device
if ! adb shell "run-as $PACKAGE id" >/dev/null 2>&1; then
  echo "旧应用不支持 adb run-as（可能不是 CI 调试版），无法自动读取应用私有数据库；未卸载或修改任何内容。" >&2
  exit 1
fi
echo "正在备份本地数据库（请先在手机上退出应用，避免更新过程中读取）..."
adb exec-out run-as "$PACKAGE" cat databases/wzyp_snapshot_v1.db > "$DB"
if [[ ! -s "$DB" ]]; then
  echo "备份失败：未获得数据库文件；未删除旧应用。" >&2
  rm -f "$DB"
  exit 1
fi
python3 - "$DB" <<'PY'
import sqlite3,sys,os
file=sys.argv[1]
db=sqlite3.connect(f"file:{file}?mode=ro",uri=True)
try:
    integrity=db.execute("PRAGMA integrity_check").fetchone()[0]
    rows={}
    for table in ("wzyp_shops","wzyp_goods","wzyp_goods_detail"):
        rows[table]=db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
    if integrity!="ok" or rows["wzyp_shops"]<1:
        raise RuntimeError("SQLite 完整性检查或核心表检查失败")
    print("备份校验通过：",rows,"大小:",os.path.getsize(file),"bytes")
finally:db.close()
PY
echo "数据库备份成功：$DB"
echo "还需在旧版应用内导出聊天记录。确认备份安全后，再人工卸载旧版并安装新的稳定签名版。"
echo "新版本设置页 -> 从手机导入 SQLite，选择此备份文件即可恢复本地数据库。"
