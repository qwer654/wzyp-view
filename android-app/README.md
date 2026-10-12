# wzyp-view Android v3.0.0 · 离线数据 + 自定义 AI API

Android 8.0+。APK 内置 SQLite，统计、图表、店铺/商品筛选、详情等始终本地运行。AI 聊天可以由用户自行接入兼容接口。

## 在手机上配置通用大模型

安装后打开 **设置 → 通用大模型 API**：

1. 选择 **通用大模型 API** 模式。
2. **Base URL** 输入服务商的 OpenAI 兼容地址，例如 `https://api.example.com/v1`；也支持完整的 `https://api.example.com/v1/chat/completions`。
3. **Model** 填写实际模型 ID，例如 `deepseek-chat`、`qwen-plus`（请以所用服务商实际支持的模型为准）。
4. **API Key** 填写你的密钥，或对局域网内无鉴权的服务留空。
5. 点击 **保存配置 → 测试连接**，然后进入 **AI 分析** 提问。

API 格式：`POST /chat/completions`，JSON 请求包含 `model`、`messages` 和 `stream:false`；响应读取 `choices[0].message.content`，当前为**非流式**完整回复。支持提供 OpenAI 兼容接口的 OpenAI、DeepSeek、Qwen、OpenRouter、Ollama 网关等。Anthropic 原生 Messages、Google Gemini 原生接口及 OpenAI Responses API **不能直接当作兼容的 Chat Completions 使用**。

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
gradle -p android-app --no-daemon assembleDebug
```

构建使用上游固定版本数据包（仅 CI 在编译时联网下载），打包成 `seed.db`。Debug 签名在不同 CI 运行之间不保证相同，如覆盖安装遇到签名不符，先备份旧应用数据，再卸载重装。正式发行需配置稳定的签名证书。
