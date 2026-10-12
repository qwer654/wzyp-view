import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

from tools.build_delta_feed import make_feed


class DeltaFeedTests(unittest.TestCase):
    @staticmethod
    def build(path, shops, goods, details):
        with sqlite3.connect(path) as con:
            con.executescript("""
            CREATE TABLE wzyp_shops(token TEXT,nickname TEXT,goods_count INTEGER);
            CREATE TABLE wzyp_goods(goods_key TEXT,shop_token TEXT,price REAL);
            CREATE TABLE wzyp_goods_detail(goods_key TEXT,name TEXT,description TEXT);
            """)
            con.executemany("INSERT INTO wzyp_shops VALUES (?,?,?)", shops)
            con.executemany("INSERT INTO wzyp_goods VALUES (?,?,?)", goods)
            con.executemany("INSERT INTO wzyp_goods_detail VALUES (?,?,?)", details)

    def test_versioned_upserts_and_deletions(self):
        with tempfile.TemporaryDirectory() as tmp:
            old, new = Path(tmp)/"old.db", Path(tmp)/"new.db"
            self.build(old,[("a","旧店名",2),("b","将移除",1)],
                [("1","a",10.0),("2","b",20.0)],[("1","A","旧详情"),("2","B","删除")])
            self.build(new,[("a","新店名",2),("c","新增",1)],
                [("1","a",12.5),("3","c",30.0)],[("1","A","新详情"),("3","C","新增")])
            delta,summary=make_feed(old,new,"rev1","")
            self.assertEqual(delta["schema_version"],1)
            self.assertEqual(delta["base_revision"],"")
            self.assertEqual(delta["revision"],"rev1")
            self.assertEqual(delta["changes"]["wzyp_shops"]["delete"],["b"])
            self.assertEqual(delta["changes"]["wzyp_goods"]["delete"],["2"])
            self.assertEqual(delta["changes"]["wzyp_goods_detail"]["delete"],["2"])
            self.assertEqual(summary["wzyp_shops"]["upsert"],2)
            self.assertEqual(summary["wzyp_goods"]["upsert"],2)
            self.assertEqual(summary["wzyp_goods_detail"]["upsert"],2)
            self.assertEqual(delta["changes"]["wzyp_goods"]["upsert"][0]["price"],12.5)
            json.dumps(delta,ensure_ascii=False)

    def test_no_change_yields_empty_lists(self):
        with tempfile.TemporaryDirectory() as tmp:
            a,b=Path(tmp)/"a.db",Path(tmp)/"b.db"
            values=[("a","同店",1)]
            self.build(a,values,[("1","a",10)],[("1","A","正文")])
            self.build(b,values,[("1","a",10)],[("1","A","正文")])
            feed,_=make_feed(a,b,"v2","v1")
            for spec in feed["changes"].values():
                self.assertEqual(spec["upsert"],[])
                self.assertEqual(spec["delete"],[])


if __name__=="__main__":
    unittest.main()
