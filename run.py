"""wzyp-view 启动入口（容器内外通用；HOST 可环境变量覆盖）"""
import os

import uvicorn

if __name__ == "__main__":
    host = os.environ.get("WZYP_HOST", "127.0.0.1")
    port = int(os.environ.get("WZYP_PORT", "8770"))
    uvicorn.run("server:app", host=host, port=port, log_level="info")
