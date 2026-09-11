"""数据库访问层：连接池 + 查询函数"""
import json
import re
import threading

import pymysql
from dbutils.pooled_db import PooledDB

import config as _config


def _build_pool():
    return PooledDB(
        creator=pymysql, maxconnections=6, mincached=1, maxcached=3,
        blocking=True, ping=1, **_config.get_db_config())


_pool = _build_pool()


def rebuild_pool():
    """DB 配置变更后重建连接池"""
    global _pool
    try:
        _pool.connections.close()
    except Exception:
        pass
    _pool = _build_pool()

def query(sql, args=None, one=False):
    conn = _pool.connection()
    try:
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute(sql, args or ())
            rows = cur.fetchall()
        return (rows[0] if rows else None) if one else rows
    finally:
        conn.close()


# ---------------- 数据 API 查询 ----------------

SHOP_SORTABLE = {"goods_count", "sell_count", "deposit_money", "create_time", "card_count",
                 "article_count", "resource_count", "equity_count", "auth_status", "avg_price",
                 "min_price", "max_price", "nickname", "token"}
GOODS_SORTABLE = {"price", "market_price", "name", "goods_type", "status", "shop_name"}
OPS = {"gt": ">", "gte": ">=", "lt": "<", "lte": "<=", "eq": "=", "ne": "!=",
       "like": "LIKE", "in": "IN"}


def build_filters(specs, column_map):
    """specs: [{field, op, value}] → (where_sql, args)。字段白名单映射，防注入。"""
    clauses, args = [], []
    for sp in specs or []:
        col = column_map.get(sp.get("field"))
        op = OPS.get(sp.get("op"))
        if not col or not op:
            continue
        v = sp.get("value")
        if v is None or v == "":
            continue
        if op == "LIKE":
            clauses.append(f"{col} LIKE %s"); args.append(f"%{v}%")
        elif op == "IN":
            vals = v if isinstance(v, list) else [x for x in str(v).split(",") if x]
            ph = ",".join(["%s"] * len(vals))
            clauses.append(f"{col} IN ({ph})"); args.extend(vals)
        else:
            clauses.append(f"{col} {op} %s"); args.append(v)
    return " AND ".join(clauses) or "1=1", args


def list_shops(kw=None, filters=None, fetch_status=None, sort="goods_count", order="desc", offset=0, limit=50):
    """店铺列表：关键词 + 任意字段筛选 + 抓取状态筛选 + 排序
    fetch_status: done=已抓完 / fetching=抓取中(含待抓) / empty=无商品"""
    cols = {
        "token": "s.token", "nickname": "s.nickname", "login_province": "s.login_province",
        "contact_qq": "s.contact_qq", "sell_count": "s.sell_count",
        "deposit_money": "s.deposit_money", "goods_count": "s.goods_count",
        "card_count": "s.card_count", "article_count": "s.article_count",
        "resource_count": "s.resource_count", "equity_count": "s.equity_count",
        "auth_status": "s.auth_status", "shop_newbie": "s.shop_newbie",
        "create_time": "s.create_time", "avg_price": "avg_price",
        "min_price": "min_price", "max_price": "max_price",
    }
    where, args = build_filters(filters, cols)
    if fetch_status == "done":
        where = f"({where}) AND s.fetch_done = 1 AND s.goods_count > 0"
    elif fetch_status == "fetching":
        where = f"({where}) AND s.fetch_done = 0 AND s.goods_count > 0"
    elif fetch_status == "empty":
        where = f"({where}) AND s.goods_count = 0"
    if kw:
        k = f"%{kw}%"
        where = f"({where}) AND (s.token LIKE %s OR s.nickname LIKE %s OR s.contact_qq LIKE %s)"
        args = args + [k, k, k]
    sort = sort if sort in SHOP_SORTABLE else "goods_count"
    direction = "ASC" if order == "asc" else "DESC"
    sort_col = {"avg_price": "avg_price", "min_price": "min_price", "max_price": "max_price"}.get(
        sort, f"s.{sort}")
    total = query(f"""SELECT COUNT(*) AS c FROM (
        SELECT s.token, (SELECT MIN(g.price) FROM wzyp_goods g WHERE g.shop_token=s.token) AS min_price,
               (SELECT MAX(g.price) FROM wzyp_goods g WHERE g.shop_token=s.token) AS max_price,
               (SELECT ROUND(AVG(g.price),2) FROM wzyp_goods g WHERE g.shop_token=s.token) AS avg_price
        FROM wzyp_shops s WHERE {where}) t""", args, one=True)["c"]
    rows = query(f"""
        SELECT s.token, s.nickname, s.login_province, s.contact_qq, s.contact_wechat,
               s.sell_count, s.deposit_money, s.goods_count, s.card_count, s.article_count,
               s.resource_count, s.equity_count, s.auth_status, s.shop_newbie,
               DATE_FORMAT(s.create_time,'%%Y-%%m-%%d') AS create_time, s.link,
               s.description, s.avatar,
               (SELECT MIN(g.price) FROM wzyp_goods g WHERE g.shop_token=s.token) AS min_price,
               (SELECT MAX(g.price) FROM wzyp_goods g WHERE g.shop_token=s.token) AS max_price,
               (SELECT ROUND(AVG(g.price),2) FROM wzyp_goods g WHERE g.shop_token=s.token) AS avg_price,
               (SELECT COUNT(*) FROM wzyp_goods g WHERE g.shop_token=s.token) AS fetched_count,
               s.fetch_done
        FROM wzyp_shops s WHERE {where}
        ORDER BY {sort_col} {direction}, s.token ASC
        LIMIT %s OFFSET %s""", args + [limit, offset])
    return {"total": total, "rows": rows}


def list_goods(shop_token=None, goods_type=None, kw=None, price_min=None, price_max=None,
               filters=None, sort="price", order="asc", offset=0, limit=50):
    """商品列表：多条件筛选 + 任意字段筛选 + 排序"""
    cols = {
        "goods_key": "g.goods_key", "shop_token": "g.shop_token", "goods_type": "g.goods_type",
        "name": "g.name", "price": "g.price", "market_price": "g.market_price",
        "status": "g.status", "shop_name": "s.nickname",
    }
    where, args = build_filters(filters, cols)
    extra = []
    if shop_token:
        extra.append("g.shop_token = %s"); args_extra = [shop_token]
    else:
        args_extra = []
    if goods_type:
        extra.append("g.goods_type = %s"); args_extra.append(goods_type)
    if kw:
        extra.append("g.name LIKE %s"); args_extra.append(f"%{kw}%")
    if price_min is not None:
        extra.append("g.price >= %s"); args_extra.append(price_min)
    if price_max is not None:
        extra.append("g.price <= %s"); args_extra.append(price_max)
    if extra:
        where = f"({where}) AND ({' AND '.join(extra)})"
        args = args + args_extra
    sort = sort if sort in GOODS_SORTABLE else "price"
    sort_col = {"shop_name": "s.nickname", "type": "g.goods_type"}.get(sort, f"g.{sort}")
    direction = "DESC" if order == "desc" else "ASC"
    total = query(f"SELECT COUNT(*) AS c FROM wzyp_goods g JOIN wzyp_shops s "
                  f"ON s.token=g.shop_token WHERE {where}", args, one=True)["c"]
    rows = query(f"""
        SELECT g.id, g.goods_key, g.shop_token, s.nickname AS shop_name, g.goods_type,
               g.name, g.price, g.market_price, g.status, g.link
        FROM wzyp_goods g JOIN wzyp_shops s ON s.token=g.shop_token
        WHERE {where}
        ORDER BY {sort_col} {direction}, g.id ASC
        LIMIT %s OFFSET %s""", args + [limit, offset])
    return {"total": total, "rows": rows}


def goods_detail(goods_key):
    d = query("SELECT * FROM wzyp_goods_detail WHERE goods_key=%s", [goods_key], one=True)
    return d


def shop_stats():
    """总览统计"""
    return query("""
        SELECT
          (SELECT COUNT(*) FROM wzyp_shops) AS shop_count,
          (SELECT COUNT(*) FROM wzyp_goods) AS goods_count,
          (SELECT COUNT(*) FROM wzyp_goods_detail) AS detail_count,
          (SELECT IFNULL(SUM(sell_count),0) FROM wzyp_shops) AS total_sell,
          (SELECT IFNULL(SUM(deposit_money),0) FROM wzyp_shops) AS total_deposit,
          (SELECT ROUND(AVG(price),2) FROM wzyp_goods WHERE price IS NOT NULL) AS avg_price,
          (SELECT ROUND(MIN(price),2) FROM wzyp_goods WHERE price IS NOT NULL) AS min_price,
          (SELECT ROUND(MAX(price),2) FROM wzyp_goods WHERE price IS NOT NULL) AS max_price
    """, one=True)


def type_dist():
    """商品类型分布"""
    return query("SELECT goods_type, COUNT(*) AS count, ROUND(AVG(price),2) AS avg_price "
                 "FROM wzyp_goods GROUP BY goods_type")


def price_buckets():
    """价格区间分布"""
    return query("""
        SELECT bucket, COUNT(*) AS count FROM (
          SELECT CASE
            WHEN price < 10 THEN '<10'
            WHEN price < 50 THEN '10-50'
            WHEN price < 100 THEN '50-100'
            WHEN price < 500 THEN '100-500'
            WHEN price < 2000 THEN '500-2000'
            ELSE '>=2000' END AS bucket
          FROM wzyp_goods WHERE price IS NOT NULL) t
        GROUP BY bucket ORDER BY FIELD(bucket,'<10','10-50','50-100','100-500','500-2000','>=2000')""")


def top_keywords(n=30):
    """商品名热词（简单分词：提取 ASCII/中文词根）"""
    rows = query("SELECT name FROM wzyp_goods")
    freq = {}
    for r in rows:
        for w in re.findall(r"[A-Za-z]{3,12}|[\u4e00-\u9fa5]{2,4}", r["name"] or ""):
            freq[w] = freq.get(w, 0) + 1
    top = sorted(freq.items(), key=lambda x: -x[1])[:n]
    return [{"word": w, "count": c} for w, c in top]


# ---------------- LangGraph 工具（只读 SQL）----------------

SAFE_SELECT = re.compile(r"^\s*SELECT\b", re.I)


def run_readonly_sql(sql, limit=200):
    """供 Agent 使用的只读查询工具：仅允许单条 SELECT，强制 LIMIT。
    SQL 报错（如表名/列名写错）时返回结构化纠正提示而非裸错误。"""
    sql = (sql or "").strip().rstrip(";")
    if not SAFE_SELECT.match(sql):
        return {"error": "仅允许 SELECT 查询"}
    if re.search(r"\bINTO\s+(OUT|DUMP)FILE\b", sql, re.I):
        return {"error": "禁止文件导出"}
    if not re.search(r"\bLIMIT\s+\d+\s*$", sql, re.I):
        sql = f"SELECT * FROM ({sql}) AS _sub LIMIT {limit}"
    try:
        rows = query(sql)
        return {"rows": rows[:limit], "count": len(rows)}
    except Exception as e:
        err = str(e)
        # 表名/列名写错 → 附上正确 schema 让模型自我纠正
        hint = ""
        if "doesn't exist" in err or "Unknown column" in err or "Unknown table" in err:
            hint = ("\n【纠正提示】你引用了不存在的表或列。本库仅有 3 张表："
                    "wzyp_shops(店铺)、wzyp_goods(商品)、wzyp_goods_detail(商品详情)。"
                    "请先调用 data_schema 核对正确表名列名后重写 SQL。")
        return {"error": f"SQL 错误: {err}{hint}"}


def schema_text():
    """表结构说明（给 LLM 的 schema 卡片）——行数动态读取，含 few-shot SQL 示例防表名幻觉"""
    try:
        n1 = query("SELECT COUNT(*) AS c FROM wzyp_shops", one=True)["c"]
        n2 = query("SELECT COUNT(*) AS c FROM wzyp_goods", one=True)["c"]
        n3 = query("SELECT COUNT(*) AS c FROM wzyp_goods_detail", one=True)["c"]
    except Exception:
        n1, n2, n3 = "?", "?", "?"
    return f"""## 数据库只有以下 3 张表（不存在其他表，禁止编造 products/shops/goods 等表名）

表：wzyp_shops（店铺，{n1}行）
- token VARCHAR 主键(店铺标识) nickname 店名 login_province 注册省份
- contact_qq / contact_wechat 联系方式 sell_count 销量 deposit_money 保证金
- goods_count 商品总数 card_count/article_count/resource_count/equity_count 各类型商品数
- auth_status 实名状态 shop_newbie 新手店 fetch_done 抓取完成 create_time 开店时间
- link 店铺链接（https://wzyp.cn/shop/<token>） description 简介

表：wzyp_goods（在售商品，{n2}行）
- goods_key 唯一标识 shop_token→wzyp_shops.token goods_type∈(card卡密/article文章/resource资源/equity权益)
- name 商品名 price 价格 market_price 市场价 status 状态 link 商品链接(https://wzyp.cn/item/<goods_key>)

表：wzyp_goods_detail（商品详情，{n3}行）
- goods_key 主键(→wzyp_goods.goods_key) name price real_price image description(HTML全文)

## 关联
wzyp_goods.shop_token = wzyp_shops.token
wzyp_goods_detail.goods_key = wzyp_goods.goods_key

## 类型中英映射（goods_type 字段存英文）
card=卡密 article=文章 resource=资源 equity=权益

## 示例 SQL（照此模式写，禁止发明新表/新列）
-- 店铺名称+链接列表：
SELECT nickname AS 店铺, link AS 链接 FROM wzyp_shops ORDER BY sell_count DESC LIMIT 20
-- 某类型商品中哪些店铺价格低于 X：
SELECT s.nickname AS 店铺, g.name AS 商品, g.price FROM wzyp_goods g
JOIN wzyp_shops s ON s.token=g.shop_token
WHERE g.goods_type='equity' AND g.price < 20 ORDER BY g.price LIMIT 50
-- 某类型平均价与最高价：
SELECT ROUND(AVG(g.price),2) AS 均价, MAX(g.price) AS 最高价 FROM wzyp_goods g WHERE g.goods_type='resource'
-- 某类型占比最高的店铺（按该店此类商品数排序）：
SELECT s.nickname AS 店铺, COUNT(*) AS 该类商品数 FROM wzyp_goods g
JOIN wzyp_shops s ON s.token=g.shop_token
WHERE g.goods_type='card' GROUP BY s.token ORDER BY 该类商品数 DESC LIMIT 10"""
