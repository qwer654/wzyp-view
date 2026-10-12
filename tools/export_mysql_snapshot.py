#!/usr/bin/env python3
"""Export an existing *updated* MySQL wzyp dataset to a phone-importable SQLite file.

This is an export tool, NOT a scraper. Use a MySQL database you already manage.
Do not expose MySQL 3306 or the original unauthenticated web admin API publicly.
"""
from __future__ import annotations

import argparse
import datetime
import decimal
import getpass
import os
import sqlite3
import sys
from pathlib import Path

TABLES=("wzyp_shops","wzyp_goods","wzyp_goods_detail")
MYSQL_TYPES={
    "tinyint":"INTEGER","smallint":"INTEGER","mediumint":"INTEGER",
    "int":"INTEGER","bigint":"INTEGER","bit":"INTEGER",
    "float":"REAL","double":"REAL","decimal":"REAL","numeric":"REAL",
}

def cast(value):
    if isinstance(value,decimal.Decimal):return float(value)
    if isinstance(value,(datetime.date,datetime.datetime)):return value.isoformat(sep=" ") if isinstance(value,datetime.datetime) else value.isoformat()
    if isinstance(value,(bytes,bytearray)):return value.decode("utf-8",errors="replace")
    if isinstance(value,bool):return int(value)
    return value

def main():
    p=argparse.ArgumentParser(description="Export a MySQL wzyp database as SQLite for the Android app")
    p.add_argument("--host",default=os.getenv("WZYP_DB_HOST","127.0.0.1"))
    p.add_argument("--port",type=int,default=int(os.getenv("WZYP_DB_PORT","3306")))
    p.add_argument("--user",default=os.getenv("WZYP_DB_USER","root"))
    p.add_argument("--database",default=os.getenv("WZYP_DB_DATABASE","wzyp"))
    p.add_argument("--password",default=os.getenv("WZYP_DB_PASSWORD"))
    p.add_argument("--output",default="wzyp-latest.db")
    args=p.parse_args()
    try: import pymysql
    except ImportError:
        sys.exit("Please install: pip install pymysql")
    password=args.password if args.password is not None else getpass.getpass("MySQL password (not echoed): ")
    dest=Path(args.output).expanduser().resolve()
    dest.parent.mkdir(parents=True,exist_ok=True)
    tmp=dest.with_suffix(dest.suffix+".tmp")
    if tmp.exists():tmp.unlink()
    mysql=pymysql.connect(host=args.host,port=args.port,user=args.user,password=password,
                          database=args.database,charset="utf8mb4",connect_timeout=15)
    local=sqlite3.connect(tmp)
    try:
        for table in TABLES:
            with mysql.cursor() as c:
                c.execute("SHOW COLUMNS FROM "+table)
                fields=c.fetchall()
            if not fields:raise RuntimeError(f"Missing MySQL table {table}")
            cols=[f[0] for f in fields]
            ddl=[]
            for col,datatype,*_ in fields:
                base=datatype.split("(")[0].lower().split()[0]
                kind=MYSQL_TYPES.get(base,"TEXT")
                ddl.append('"'+col.replace('"','""')+'" '+kind)
            local.execute("CREATE TABLE "+table+" ("+",".join(ddl)+")")
            quoted=",".join('"'+col.replace('"','""')+'"' for col in cols)
            mysql_quoted=",".join("`"+col.replace("`","``")+"`" for col in cols)
            placeholders=",".join("?" for _ in cols)
            insert=f"INSERT INTO {table} ({quoted}) VALUES ({placeholders})"
            rows_count=0
            # Server-side cursor reads large MySQL tables incrementally.
            with mysql.cursor(pymysql.cursors.SSCursor) as cur:
                cur.execute("SELECT "+mysql_quoted+" FROM "+table)
                while True:
                    chunk=cur.fetchmany(500)
                    if not chunk:break
                    local.executemany(insert,[[cast(v) for v in row] for row in chunk])
                    rows_count+=len(chunk)
            local.commit()
            print(table,rows_count)
        local.execute("CREATE INDEX idx_goods_shop ON wzyp_goods(shop_token)")
        local.execute("CREATE INDEX idx_goods_price ON wzyp_goods(price)")
        local.execute("CREATE INDEX idx_goods_type ON wzyp_goods(goods_type)")
        local.execute("CREATE INDEX idx_goods_key ON wzyp_goods(goods_key)")
        local.execute("CREATE INDEX idx_details_key ON wzyp_goods_detail(goods_key)")
        local.commit()
        local.execute("VACUUM")
        integrity=local.execute("PRAGMA integrity_check").fetchone()[0]
        count=local.execute("SELECT COUNT(*) FROM wzyp_goods").fetchone()[0]
        shops=local.execute("SELECT COUNT(*) FROM wzyp_shops").fetchone()[0]
        if integrity!="ok" or shops==0 or count==0:
            raise RuntimeError("Exported dataset was invalid/empty")
    except Exception:
        local.close()
        if tmp.exists():tmp.unlink()
        raise
    else:
        local.close()
        tmp.replace(dest)
        print(f"Database snapshot written: {dest} ({dest.stat().st_size} bytes)")
        print("Copy this .db file to your phone and import via Android Settings > 更新数据库.")
    finally:
        mysql.close()

if __name__=="__main__":
    main()
