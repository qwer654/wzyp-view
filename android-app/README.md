# wzyp-view Android v8.0.1 · 公开店铺直接刷新 / SQLite 增量同步 / 自选 AI API

Android 8.0+。APK 内置 SQLite，统计、图表、店铺/商品筛选、详情等始终本地运行。AI 聊天可以由用户自行接入兼容接口。


## v8.0.1 · 自动更新保存失败修复

- 补全 Android Manifest 的 `android.permission.ACCESS_NETWORK_STATE`。后台自动更新使用 `JobScheduler.NETWORK_TYPE_UNMETERED` / `NETWORK_TYPE_ANY`，Android 要求此权限；它是普通权限，不需要用户到“应用权限”页面手动开通。
- CI 对已编译 APK 强制检查 `INTERNET`、`ACCESS_NETWORK_STATE` 以及两个后台任务 Service。另有不依赖私钥的[编译验证工作流](../.github/workflows/android-permission-smoke.yml)，确保修复能通过 Android 编译。
- 系统拒绝安排任务时，APP 不再错误显示“保存成功”。
- 正式 v8.0.1 需要仓库管理员配置固定签名 GitHub Secrets 才会产出，缺少签名密钥时不会发布另一个不能升级的临时 Debug APK。

## v8 固定签名（解决不能覆盖安装）

原先的 GitHub CI 仅执行 `assembleDebug`，运行器产生临时签名，因此不同构建的 APK 无法保证在同一部手机上覆盖安装。v8 改为固定发布签名，严格验证证书指纹。**需要仓库管理员先在 GitHub Secrets 中配置两项私密信息**，未配置则 CI 会报错而不会发布不兼容的 APK。

请查看 [固定签名及迁移指南](SIGNING.md)。一次性迁移时请备份旧版聊天、配置及重要本地数据；未保留旧签名私钥时，Android 不支持原位覆盖 v1–v7 的旧安装包。

## v8.0.2 数据更新与启动稳定性修复

- **统一 SQLite 管理实例**：前台浏览、定时公开店铺更新及自定义 JSON 增量更新共享同一个 `OfflineStore`，避免背景事务更新后 UI 仍使用过期数据库句柄或统计缓存。
- **中断安全**：Android 系统停止公开店铺后台任务时，停止网络请求并在提交该店数据库更新前检查取消状态；手动刷新新增“取消本次刷新”，未完成的店铺保持原数据。手动单店刷新结果正确显示一店而非 `undefined`。
- **导出当前数据库**：设置 → 更新店铺和商品数据库 → “导出当前 SQLite 备份”，通过安卓系统保存对话框另存 `.db`。导出前执行 `PRAGMA quick_check` 并拒绝不安全的 WAL 文件直接复制，导出期间与后台 SQLite 写入串行，避免数据不一致。导入功能仍然保留。
- **半途失败数据刷新**：一批店铺前几个已更新、后续请求失败时，尝试刷新总览统计，但不把整批标记为成功。
- **构建防回归**：新增自动化源代码契约测试，以及带固定发布签名证书的 APK 包名、签名及权限检查。

以上源代码修复已添加到仓库，GitHub Actions 验证编译；**网络端公开商品接口可能仍返回 HTML / 登录验证页**，在平台未开放前无法保证端到端刷新。不会进行平台风控绕过。

## 在手机上配置通用大模型

安装后打开 **设置 → 通用大模型 API**：

1. 选择 **通用大模型 API** 模式。
2. **Base URL** 输入服务商的 OpenAI 兼容地址，例如 `https://api.example.com/v1`；也支持完整的 `https://api.example.com/v1/chat/completions`。
3. **Model** 填写实际模型 ID，例如 `deepseek-chat`、`qwen-plus`（请以所用服务商实际支持的模型为准）。
4. **API Key** 填写你的密钥，或对局域网内无鉴权的服务留空。
5. 点击 **保存配置 → 测试连接**，然后进入 **AI 分析** 提问。

API 格式：`POST /chat/completions`，JSON 请求包含 `model`、`messages` 和 `stream:false`；响应读取 `choices[0].message.content`，当前为**非流式**完整回复。支持提供 OpenAI 兼容接口的 OpenAI、DeepSeek、Qwen、OpenRouter、Ollama 网关等。Anthropic 原生 Messages、Google Gemini 原生接口及 OpenAI Responses API **不能直接当作兼容的 Chat Completions 使用**。

## 怎么更新店铺 / 商品数据库？

v4 版在 **设置 → 更新店铺和商品数据库** 提供以下选项：

1. **从手机导入 SQLite**：在手机文件管理器中选择你准备好的完整 `.db` 或 `.sqlite` 文件。无需联网。
2. **HTTPS 下载并更新**：填写自己托管的、直接返回 SQLite 二进制文件的 HTTPS URL，点击下载更新。下载后数据存到手机，之后离线可用。
3. **恢复 APK 内置数据**：恢复到安装包附带的旧历史快照（覆盖此前导入的数据）。

更新会复制至暂存文件，做 SQLite `integrity_check` 和三张表结构校验，并在失败时恢复旧库；成功后即时刷新统计、图表和商品列表。保留聊天会话与 API Key。文件大小上限 600 MB。导入是**完整数据库快照替换**，当前不提供逐行增量同步。

**重要：原项目本身是一个历史数据查看器，没有持续抓取和自动更新服务。单靠升级 APK 或点更新按钮不会产生最新平台数据，必须自行提供新版 SQLite 或能生成它的 MySQL 数据源。** 大模型 API 与数据库同步是两套独立功能；仅配置 AI Key 不会更新店铺商品。

### 如果你有最新 MySQL 数据库（原 PC 版部署）

在拥有 MySQL 访问权限的电脑上：

```bash
pip install pymysql
python tools/export_mysql_snapshot.py --host 127.0.0.1 --port 3306 --user root --database wzyp --output wzyp-latest.db
```

工具会交互式询问 MySQL 密码；也可以用 `WZYP_DB_PASSWORD` 环境变量传入。它导出三张表 `wzyp_shops` / `wzyp_goods` / `wzyp_goods_detail`，生成可被 APK 导入的 SQLite 文件。将其复制到手机或上传至你自己的 HTTPS 文件服务器，即可更新。

如果你只拥有原仓库的 `wzyp-data.zip` SQL 快照，也可在电脑运行 `WZYP_DATA_ZIP=/your/path/wzyp-data.zip python tools/build_offline_db.py`，它会生成 `android-app/app/src/main/assets/seed.db`（**只是该 ZIP 的对应历史数据，并非实时更新**）。

不要把数据库、MySQL 密码、原始服务端管理接口直接公开到互联网。对数据库下载链接设置访问控制，HTTPS 只解决传输加密，不保证文件由可信方提供。

## v6：直接刷新链动小铺公开店铺商品（无需自备更新 JSON）

v6 新增 **设置 → 官方公开店铺 · 直接刷新**。它尝试直接通过链动小铺的公开店铺商品列表接口 `POST https://wzyp.cn/shopApi/Shop/goodsList` 获取数据；参数含本地已有店铺的 token、商品类型、分页等。接口信息参考开源项目 [qion888/ldsub2api](https://github.com/qion888/ldsub2api) 的 `backend/monitor_core/storefront.py`。

使用方式：

1. 手机联网，打开「设置 → 官方公开店铺 · 直接刷新」。
2. **留空店铺输入框**：自动从已知的 250 家店铺中顺序刷新下一批 4 或 8 家；上次成功的位置本机持久保存，可多次点击继续刷新。
3. **填入已有店铺 Token 或 `https://wzyp.cn/shop/TOKEN`**：仅刷新这家店。
4. **自动更新开关**：明确启用后，Android JobScheduler 每 24 小时左右、仅在非计费网络/Wi-Fi 条件下最多检查 4 家已知店铺；Android 省电策略和厂商限制可能推迟或取消后台执行。
5. 从公开商品列表提取 `goods_key`、商品名、价格、市场价、商品类型及上架状态，使用 SQLite 事务更新。新商品插入已有店铺，已有记录逐字段更新；遇到平台拒绝、验证码、HTML 验证页、解析失败或请求不完整时，不提交**该店**的数据。

为避免对网站施加不必要负担，所有列表请求发起时间全局间隔至少 1 秒，同时最多 2 路并行隐藏网络等待时间；每种商品类型最多 6 页 × 50 条，超出即停止该店同步而不写入残缺快照。**不会登录、绕过 WAF 或 CAPTCHA，也不调用商家私有 API。**

### 本次直接刷新尚不包含

- **全站新增店铺自动发现**：批量自动刷新以已有店铺 token 为起点；也支持用户粘贴新店铺 URL，在公开商品列表返回有效记录后加入 SQLite。仍不能自动发现全站未收录的店铺。
- 商品详情全文、图片文件自动下载及补全：公开列表主要提供商品简要信息；历史详情保留原样。
- 尚未验证的全站接口：如果官网接口发生变化或要求登录验证，应用会显示具体失败信息，而不是伪造更新成功。
- “直接刷新”与自选大模型 API 两个功能完全分离，**无需付费 AI Key**。

## v7 · 性能与稳定性优化

- 同一店铺的四种商品分类，不再完全串行，而是**最多 2 个并行读取线程**。全局限流器确保同进程前台/后台请求的发起时间相隔**至少 1 秒**，不进行无限并发；遇平台拒绝或验证立即停止，不绕过限制。
- 分页仍按平台的 `total`、已请求数量和每页实际记录数提前结束，避免多余请求。页面上会显示**实测请求数及耗时**，但提速倍数取决于接口延迟和类别数量。
- 本地 SQLite 将同店旧商品**一次读取并建立内存索引**；仅针对新增/修改记录写库，无变化记录不再重新构造原始数据。
- 公网接口未被验证能在所有网络下稳定返回 JSON；若服务器返回 HTML、429 或登录挑战，加速算法并不能使该接口可用。

## v5 增量同步：不用重复下载整库

安卓版设置页新增 **“增量同步 · 自动检查”**。

1. 把数据源的最新 SQLite 快照与上一版 SQLite 快照准备好（或者使用具有这些表的你自己的数据库导出）。
2. 用仓库的命令生成**版本化 JSON 变更包**：
   ```bash
   python tools/build_delta_feed.py \
     --old previous.db --new current.db \
     --base-revision "" --revision "2026-10-12-r1" \
     --output wzyp-delta.json
   ```
   第一次从 v4/内置旧库切换为增量更新时，`base_revision` 为**空字符串**。第二次变更包必须采用 `--base-revision "2026-10-12-r1"`，下一版 `revision` 也需要更新。
3. 把生成的 JSON 文件发布到**你自己控制的 HTTPS 地址**。手机打开“设置 → 增量同步”，填入 URL 并保存。
4. 点击 **立即检查并同步**；你也可以选择 **开启自动检查**，Android JobScheduler 会按大约 12 小时的周期在有网络时安排检查。实际执行时间受 Android 省电策略影响。用户关闭开关后会取消后台任务。
5. 版本无变化或服务器返回 HTTP 304 时不写库；版本变化时按键进行新增/修改/删除，使用 SQLite 事务，失败全部回滚。更新完成后当前统计自动刷新。

### 数据源协议（版本 1）

HTTPS 响应 JSON 必须是以下结构。**不能直接填写 wzyp.cn 首页、第三方 AI API 地址或任意 MySQL 接口。**

```json
{
  "schema_version": 1,
  "base_revision": "",
  "revision": "2026-10-12-r1",
  "changes": {
    "wzyp_shops": {
      "upsert": [{"token": "shop_a", "nickname": "示例店铺", "goods_count": 1}],
      "delete": []
    },
    "wzyp_goods": {
      "upsert": [{"goods_key": "item_a", "shop_token": "shop_a",
                  "goods_type": "resource", "name": "示例商品", "price": 12}],
      "delete": []
    },
    "wzyp_goods_detail": {
      "upsert": [],
      "delete": []
    }
  }
}
```

建议直接用 `tools/build_delta_feed.py` 从完整的旧/新 SQLite 导出；它会包含所有字段。简化 JSON 例子仅说明协议，并非一套完整快照。所有表名/列名均须存在于手机数据库。单份变更包最大 35 MB；如果有大量改动，请使用 v4 提供的**完整 SQLite 文件导入**，之后再以空 `base_revision` 建立新的增量链。

**数据不会凭空更新**：项目上游只提供历史快照，没有公开可直接验证的完整持续同步服务；你需要维护更新过的数据库或提供自有数据源。这个功能不会自动登录网站、抓取页面、绕过平台登录/限制。定期检查同步 URL 与大模型 API 完全独立；API Key 不作为增量接口凭证，也不会被发送给同步服务器。

## 离线保留能力

- 250 家店铺、11,042 件商品、10,769 条详情（上游 v1.0.0 的固定历史快照）。
- 数据查询、筛选、排序、图表始终在手机 SQLite 执行。
- 断网时可手动切换到 **本地规则分析**，继续基本统计问答。
- 会话保存在手机私有存储，可导入和导出聊天备份。

## 安全与隐私

- Android `INTERNET` 权限仅供用户自行配置的原生 API 请求使用；WebView 页面仍禁止网络加载。
- API Key 经 Android Keystore AES-GCM 加密存储，设置页不回显原 Key，聊天备份不包含 Key。
- 公网只允许 HTTPS；HTTP 仅用于本机或可信局域网模型服务（HTTP 传输本身不加密）。
- 使用在线 AI 时，当前问题、最近聊天记录和经过筛选的历史统计/商品数据将发送给所配置服务商，以提供数据依据；完整数据库、联系方式、raw_json、Key 不作为上下文发送。
- 接口失败显示错误，不会默默伪装为本地结果；用户可选择离线规则模式。
- 固定历史数据不代表实时库存或价格；若模型所需证据不足，应提示在本机继续筛选查看。

## 构建与 APK

由 [Android APK workflow](../../actions/workflows/android-apk.yml) 构建并通过 [Releases](../../releases) 发布。

本地开发依次执行：

```bash
python3 tools/build_offline_db.py
python3 tools/build_offline_web.py
python3 tools/enable_api_ui.py
python3 tools/enable_db_update_ui.py
python3 tools/enable_delta_sync_ui.py
python3 tools/enable_public_shop_ui.py
gradle -p android-app --no-daemon assembleDebug
```

构建使用上游固定版本数据包（仅 CI 在编译时联网下载），打包成 `seed.db`。Debug 签名在不同 CI 运行之间不保证相同，如覆盖安装遇到签名不符，先备份旧应用数据，再卸载重装。正式发行需配置稳定的签名证书。
