"""wzyp-view — 链动小铺(wzyp.cn)店铺数据可视化 + LangGraph AI 分析"""
import json
import os

BASE_DIR = os.path.dirname(os.path.abspath(__file__))

# settings.json 落在 data/ 目录（容器 volume 挂载点，页面配置可持久化）
SETTINGS_FILE = os.environ.get("WZYP_SETTINGS_FILE") or os.path.join(BASE_DIR, "data", "settings.json")

# ---- 默认配置（全部可被 settings.json / 环境变量覆盖，仓库不含任何真实凭据）----
DB_DEFAULTS = {
    "host": "127.0.0.1",
    "port": 3306,
    "user": "root",
    "password": "",
    "database": "wzyp",
    "charset": "utf8mb4",
}
LLM_DEFAULTS = {
    "baseurl": "http://127.0.0.1:16869/v1",   # 本机 vLLM qwen38 默认
    "model": "qwen38",
    "apikey": "",
}

# ---- LangGraph 记忆 ----
SQLITE_DIR = os.path.join(BASE_DIR, "data")
os.makedirs(SQLITE_DIR, exist_ok=True)
CHECKPOINTER_DB = os.path.join(SQLITE_DIR, "chat_memory.sqlite")


def load_settings():
    """读 settings.json。兼容旧平铺 LLM 格式 → 返回 {llm:{...}, db:{...}}"""
    data = {}
    try:
        with open(SETTINGS_FILE, encoding="utf-8") as f:
            data = json.load(f) or {}
    except Exception:
        pass
    # 旧格式：{"baseurl":..., "model":..., "apikey":...}（平铺=LLM 配置）
    if "llm" not in data and any(k in data for k in ("baseurl", "model", "apikey")):
        data = {"llm": data}
    llm = dict(LLM_DEFAULTS)
    llm.update({k: v for k, v in (data.get("llm") or {}).items() if v})
    db = dict(DB_DEFAULTS)
    db.update({k: v for k, v in (data.get("db") or {}).items() if v})
    # 环境变量覆盖（部署友好，Docker 用）
    llm["baseurl"] = os.environ.get("WZYP_LLM_BASEURL", llm["baseurl"])
    llm["model"] = os.environ.get("WZYP_LLM_MODEL", llm["model"])
    llm["apikey"] = os.environ.get("WZYP_LLM_APIKEY", llm.get("apikey") or "")
    for env_key, cfg_key in [("WZYP_DB_HOST", "host"), ("WZYP_DB_PORT", "port"),
                             ("WZYP_DB_USER", "user"), ("WZYP_DB_PASSWORD", "password"),
                             ("WZYP_DB_DATABASE", "database")]:
        if os.environ.get(env_key):
            v = os.environ[env_key]
            db[cfg_key] = int(v) if cfg_key == "port" else v
    return {"llm": llm, "db": db}


def save_settings(llm: dict | None = None, db: dict | None = None):
    """合并保存（None 段保留现值）"""
    cur = load_settings()
    if llm:
        cur["llm"].update({k: v for k, v in llm.items() if v})
    if db:
        cur["db"].update({k: v for k, v in db.items() if v})
    with open(SETTINGS_FILE, "w", encoding="utf-8") as f:
        json.dump(cur, f, ensure_ascii=False, indent=2)
    return cur


def get_db_config():
    return dict(load_settings()["db"])


def get_llm_config():
    return dict(load_settings()["llm"])


# 兼容旧引用
def _db_config_compat():
    return get_db_config()


DB_CONFIG = DB_DEFAULTS  # 仅作类型参考；运行时请用 get_db_config()
