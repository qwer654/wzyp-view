# wzyp-view — 链动小铺店铺数据洞察
FROM python:3.12-slim

WORKDIR /app

# 时区 + 基础工具
ENV TZ=Asia/Shanghai \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    WZYP_HOST=0.0.0.0

# 依赖（分层缓存；国内网络用清华 pypi 镜像，可被 build-arg 覆盖）
COPY requirements.txt .
ARG PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
RUN pip install --no-cache-dir -i ${PIP_INDEX_URL} -r requirements.txt

# 应用代码
COPY server.py agent.py db.py config.py run.py ./
COPY static/ ./static/

# 数据目录（聊天记忆 / settings.json 运行时可写）
RUN mkdir -p /app/data
VOLUME ["/app/data"]

EXPOSE 8770

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD python -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:8770/api/stats',timeout=4)" || exit 1

CMD ["python", "run.py"]
