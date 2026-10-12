#!/usr/bin/env python3
"""Generate a versioned, auditable SQLite delta feed for Android v5.

Typical workflow:
  python tools/build_delta_feed.py --old previous.db --new current.db \
    --revision 2026-10-12T21:00 --output wzyp-delta.json

Publish the JSON over a read-only HTTPS URL; keep prior revisions for devices
that miss updates, or instruct users to import a complete newer SQLite snapshot.
This script does NOT collect or scrape platform records.
"""
from __future__ import annotations
import argparse
import json
import sqlite3
from pathlib import Path

TABLES = (("wzyp_shops","token"),("wzyp_goods","goods_key"),("wzyp_goods_detail","goods_key"))
LIMIT_BYTES = 35 * 1024 * 1024

def records(conn: sqlite3.Connection, table: str, key: str):
    conn.row_factory = sqlite3.Row
    cursor = conn.execute(f"SELECT * FROM {table}")
    seen = {}
    for row in cursor:
        d = dict(row)
        val = d.get(key)
        if val is None: raise ValueError(f"{table} missing {key}")
        if val in seen: raise ValueError(f"{table} has duplicate {key}={val}")
        # JSON types only; raw binary is unsupported by Android JSON-only feed.
        for column, value in d.items():
            if isinstance(value, bytes):
                d[column] = value.decode("utf-8", "replace")
        seen[str(val)] = d
    return seen

def make_feed(old_path: Path, new_path: Path, revision: str, base_revision: str):
    old = sqlite3.connect(f"file:{old_path}?mode=ro", uri=True)
    new = sqlite3.connect(f"file:{new_path}?mode=ro", uri=True)
    try:
        feed = {"schema_version":1,"base_revision":base_revision,
                "revision":revision,"changes":{}}
        totals = {}
        for table,key in TABLES:
            before=records(old,table,key)
            after=records(new,table,key)
            deleted=sorted(set(before)-set(after))
            changed=[after[k] for k in sorted(after) if k not in before or after[k]!=before[k]]
            feed["changes"][table]={"delete":deleted,"upsert":changed}
            totals[table]={"upsert":len(changed),"delete":len(deleted)}
        return feed,totals
    finally:
        old.close()
        new.close()

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--old",type=Path,required=True,help="Previous SQLite snapshot")
    p.add_argument("--new",type=Path,required=True,help="Latest SQLite snapshot")
    p.add_argument("--revision",required=True,help="Non-empty new feed version string")
    p.add_argument("--base-revision",default="",help="Version last installed on the phone. Leave empty for an unversioned v4 snapshot")
    p.add_argument("--output",type=Path,default=Path("wzyp-delta.json"))
    opt=p.parse_args()
    if not opt.revision or len(opt.revision)>128 or opt.revision==opt.base_revision:
        p.error("revision must be non-empty, <=128 characters, different from base-revision")
    feed,summary=make_feed(opt.old,opt.new,opt.revision,opt.base_revision)
    data=json.dumps(feed,ensure_ascii=False,separators=(",",":"),allow_nan=False).encode("utf-8")
    if len(data)>LIMIT_BYTES:
        p.error(f"Delta is {len(data):,} bytes (>35 MiB). Publish the full SQLite snapshot instead.")
    opt.output.parent.mkdir(parents=True,exist_ok=True)
    temp=opt.output.with_suffix(opt.output.suffix+".tmp")
    temp.write_bytes(data)
    temp.replace(opt.output)
    print("Created",opt.output,len(data),"bytes")
    print(json.dumps(summary,ensure_ascii=False,indent=2))

if __name__=="__main__":
    main()
