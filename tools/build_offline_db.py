#!/usr/bin/env python3
"""Build a deterministic, bundled SQLite snapshot from the upstream wzyp-data release.

No remote calls occur on the Android device. Downloading takes place ONLY in GitHub CI.
The upstream SQL is data, never executed as SQL against SQLite.
"""
from __future__ import annotations

import os
import re
import sqlite3
import urllib.request
import time
import zipfile
from pathlib import Path

UPSTREAM_ARCHIVE = "https://github.com/Samge0/wzyp-view/releases/download/v1.0.0/wzyp-data.zip"
TABLES = ("wzyp_shops", "wzyp_goods", "wzyp_goods_detail")
COLUMNS = {}
DDL = re.compile(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?([\w]+)`?\s*\(", re.I)
INSERT = re.compile(r"INSERT\s+INTO\s+`?([\w]+)`?\s*(?:\(([^)]*)\)\s*)?VALUES\s*", re.I)
COLUMN = re.compile(r"^\s*`([^\x60]+)`\s+([A-Za-z]+)", re.M)
SQL_TYPE = {
    "tinyint": "INTEGER", "smallint": "INTEGER", "mediumint": "INTEGER",
    "int": "INTEGER", "bigint": "INTEGER", "float": "REAL", "double": "REAL",
    "decimal": "REAL", "numeric": "REAL", "real": "REAL",
}
BACKSLASH = {"0": "\x00", "n": "\n", "r": "\r", "t": "\t", "b": "\b", "Z": "\x1a",
             "'": "'", '"': '"', "\\": "\\"}


def parse_rows(s: str):
    """Parse MySQL VALUES tuples without evaluating or executing SQL text."""
    i, n = 0, len(s)
    while i < n:
        while i < n and s[i] in " \r\n\t,;": i += 1
        if i >= n: break
        if s[i] != "(":
            raise ValueError(f"unexpected VALUES token at {i}: {s[i:i+60]!r}")
        i += 1
        row = []
        while i < n:
            while i < n and s[i] in " \r\n\t": i += 1
            if i < n and s[i] in ("'", '"'):
                quote = s[i]
                i += 1
                chars = []
                while i < n:
                    ch = s[i]
                    i += 1
                    if ch == "\\" and i < n:
                        ch = BACKSLASH.get(s[i], s[i])
                        i += 1
                        chars.append(ch)
                    elif ch == quote:
                        if i < n and s[i] == quote:
                            chars.append(quote)
                            i += 1
                        else:
                            break
                    else:
                        chars.append(ch)
                row.append("".join(chars))
            else:
                j = i
                while i < n and s[i] not in ",)": i += 1
                value = s[j:i].strip()
                if value.upper() == "NULL": row.append(None)
                elif value.lower() in ("true", "false"): row.append(int(value.lower() == "true"))
                elif re.fullmatch(r"[-+]?\d+", value): row.append(int(value))
                elif re.fullmatch(r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?", value):
                    row.append(float(value))
                else: row.append(value)
            while i < n and s[i] in " \r\n\t": i += 1
            if i < n and s[i] == ",":
                i += 1
                continue
            if i < n and s[i] == ")":
                i += 1
                break
            raise ValueError(f"malformed SQL tuple near offset {i}")
        yield row


def create_schema(conn, sql):
    m = DDL.search(sql)
    if not m or m.group(1) not in TABLES: return None
    table = m.group(1)
    start = m.end()
    tail = sql[start:]
    # Split definition from trailing ENGINE=..., preferring the last close paren in DDL.
    body = tail.rsplit(")", 1)[0] if ")" in tail else tail
    columns = COLUMN.findall(body)
    if not columns:
        raise ValueError(f"no columns parsed in {table} CREATE TABLE")
    cols = [(name, SQL_TYPE.get(dtype.lower(), "TEXT")) for name, dtype in columns]
    COLUMNS[table] = [c[0] for c in cols]
    statement = "CREATE TABLE IF NOT EXISTS " + table + " (" + ", ".join(
        '"' + c + '" ' + kind for c,kind in cols
    ) + ")"
    conn.execute(statement)
    print("schema:", table, len(cols), ",".join(COLUMNS[table]))
    return table


def convert_insert(conn, sql):
    m = INSERT.search(sql)
    if not m: return 0
    table = m.group(1)
    if table not in TABLES: return 0
    cols = re.findall(r"`([^\x60]+)`", m.group(2)) if m.group(2) else COLUMNS.get(table)
    if not cols: raise ValueError(f"missing SQL CREATE TABLE for {table}")
    if not COLUMNS.get(table):
        # INSERT explicitly lists columns, handle atypical dumps.
        COLUMNS[table] = cols[:]
        conn.execute("CREATE TABLE IF NOT EXISTS " + table + " (" +
                     ",".join('"' + col + '" TEXT' for col in cols) + ")")
    placeholders = ",".join("?" for _ in cols)
    fields = ",".join('"' + c + '"' for c in cols)
    sql_insert = f"INSERT INTO {table} ({fields}) VALUES ({placeholders})"
    values = sql[m.end():].rstrip().rstrip(";")
    total = 0
    batch = []
    for row in parse_rows(values):
        if len(row) != len(cols):
            raise ValueError(f"{table}: tuple has {len(row)} values expected {len(cols)}")
        batch.append(row)
        if len(batch) >= 500:
            conn.executemany(sql_insert, batch)
            total += len(batch)
            batch.clear()
    if batch:
        conn.executemany(sql_insert, batch)
        total += len(batch)
    return total


def sql_statements(fp):
    """Yield only DDL/INSERT statements; ignores dump directives and comments."""
    buf = []
    capturing = False
    for line in fp:
        stripped = line.lstrip()
        if not capturing:
            if re.match(r"CREATE\s+TABLE\b|INSERT\s+INTO\b|REPLACE\s+INTO\b", stripped, re.I):
                capturing = True
            else:
                continue
        buf.append(line)
        # MySQL dumps use newline-delimited statements; a semicolon inside a value can
        # occur mid-line, but only line-ending semicolons terminate supported statements.
        if line.rstrip().endswith(";"):
            yield "".join(buf)
            buf.clear()
            capturing = False
    if buf: raise ValueError("unterminated MySQL statement")


def main():
    output = Path("android-app/app/src/main/assets/seed.db")
    output.parent.mkdir(parents=True, exist_ok=True)
    archive = Path(os.environ.get("WZYP_DATA_ZIP", "/tmp/wzyp-data.zip"))
    if not archive.is_file():
        print("Fetching pinned upstream release data for APK (build machine only)")
        last_error = None
        for attempt in range(1, 5):
            partial = archive.with_suffix(".download")
            try:
                with urllib.request.urlopen(UPSTREAM_ARCHIVE, timeout=120) as response, partial.open("wb") as dest:
                    while data := response.read(1024 * 1024):
                        dest.write(data)
                if partial.stat().st_size < 100_000:
                    raise ValueError("Unexpectedly short historical SQLite source archive")
                partial.replace(archive)
                break
            except (OSError, ValueError) as exc:
                last_error = exc
                partial.unlink(missing_ok=True)
                print(f"Source archive download attempt {attempt}/4 failed: {exc}", flush=True)
                if attempt < 4:
                    time.sleep(4 * attempt)
        if not archive.is_file():
            raise RuntimeError("Cannot fetch pinned historical dataset archive after retries") from last_error
    if archive.stat().st_size > 80 * 1024 * 1024:
        raise ValueError("unexpected archive size")
    if output.exists(): output.unlink()
    counts = dict.fromkeys(TABLES, 0)
    conn = sqlite3.connect(output)
    conn.execute("PRAGMA synchronous=OFF")
    conn.execute("PRAGMA journal_mode=OFF")
    with zipfile.ZipFile(archive) as z:
        sql_files = sorted((p for p in z.infolist() if p.filename.lower().endswith(".sql")),
                           key=lambda p: TABLES.index(next((t for t in TABLES if t in p.filename), TABLES[2])))
        if not sql_files:
            raise ValueError("archive contains no SQL files")
        for entry in sql_files:
            if entry.file_size > 200 * 1024 * 1024:
                raise ValueError("SQL file too large")
            print("Importing", entry.filename, entry.file_size)
            with z.open(entry) as raw:
                import io
                stream = io.TextIOWrapper(raw, encoding="utf-8-sig", errors="replace")
                for statement in sql_statements(stream):
                    if DDL.search(statement):
                        create_schema(conn, statement)
                    elif INSERT.search(statement):
                        m = INSERT.search(statement)
                        if m and m.group(1) in counts:
                            counts[m.group(1)] += convert_insert(conn, statement)
            conn.commit()
    for table in TABLES:
        n = conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        print("rows", table, n, "imported", counts[table])
    shops = conn.execute("SELECT COUNT(*) FROM wzyp_shops").fetchone()[0]
    goods = conn.execute("SELECT COUNT(*) FROM wzyp_goods").fetchone()[0]
    if shops < 100 or goods < 500:
        raise ValueError("dataset incomplete; refusing to publish empty/offline-only shell")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_goods_shop ON wzyp_goods(shop_token)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_goods_price ON wzyp_goods(price)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_goods_type ON wzyp_goods(goods_type)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_goods_key ON wzyp_goods(goods_key)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_details_key ON wzyp_goods_detail(goods_key)")
    conn.commit()
    conn.execute("VACUUM")
    result = conn.execute("PRAGMA integrity_check").fetchone()[0]
    conn.close()
    if result != "ok": raise ValueError("corrupt SQLite seed")
    print("Offline SQLite:", output.stat().st_size, "bytes", result)


if __name__ == "__main__":
    main()
