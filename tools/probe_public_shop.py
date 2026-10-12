#!/usr/bin/env python3
"""Smoke-check one public storefront catalog page (one HTTP request, no WAF bypass).

Reads a token from generated SQLite seed; never accesses user accounts or purchases.
Exit nonzero when remote catalog cannot be fetched; CI marks probe informative.
"""
import json
import sqlite3
import sys
from urllib.request import Request,urlopen
from urllib.error import HTTPError,URLError

DB="android-app/app/src/main/assets/seed.db"
if __import__("os").path.isfile(DB):
    with sqlite3.connect(DB) as conn:
        token=conn.execute("SELECT token FROM wzyp_shops WHERE token != '' ORDER BY token LIMIT 1").fetchone()[0]
else:
    # A publicly referenced seller: https://wzyp.cn/shop/youzhi
    token="youzhi"
print("Probing public storefront for one already-known shop; no auth or retries",flush=True)
success=False
for host in ("wzyp.cn","pay.ldxp.cn"):
    request=Request("https://"+host+"/shopApi/Shop/goodsList",
        data=json.dumps({"token":token,"keywords":"","category_id":"","goods_type":"card",
                         "current":1,"pageSize":5}).encode(),
        headers={"Content-Type":"application/json","Accept":"application/json, text/plain, */*",
                 "Origin":"https://"+host,"Referer":f"https://{host}/shop/{token}",
                 "User-Agent":"WzypView-Android/6.0"},method="POST")
    try:
        with urlopen(request,timeout=18) as response:
            content_type=response.headers.get("Content-Type","")
            body=response.read(8193)
            print(host,"HTTP",response.status,"Content-Type",content_type,"bytes",len(body))
            if len(body)>8192:raise ValueError("response >8192 bytes")
            if "html" in content_type.lower():raise ValueError("HTML or verification page; not JSON")
            d=json.loads(body)
            if d.get("code")!=1:raise ValueError("API code is not 1")
            print("VALID_PUBLIC_JSON",host,"code=1; rows parseable")
            success=True
    except (HTTPError,URLError,ValueError,TimeoutError) as e:
        print("PUBLIC_API_UNAVAILABLE",host,str(e)[:180],flush=True)
if not success:
    print("Neither public domain exposed JSON to this CI runner; Android will stop rather than bypass challenge.")
    sys.exit(1)

