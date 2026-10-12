# wzyp-view Android v2.0.0 · 离线单机版

Android 8.0+，无需网络权限，不用部署 FastAPI / MySQL / Python / LLM。

## 已实现

- 原网页全部数据浏览页面：总览、分布图、热词、店铺/商品查询、筛选、排序、分页、商品详情。
- 安装包内预置 SQLite 数据（由上游 Samge0/wzyp-view **v1.0.0 静态历史快照**的 \`wzyp-data.zip\` 在 CI 编译时转换）；
- 本地统计问答及店铺/商品查询结果分析，无外部服务器，记录保存在手机私有存储。
- 会话列表、新对话、切换、清空/删除聊天、导入/导出本地聊天备份（使用系统分享界面）。
- 手机竖屏适配，Android 系统文件选择。

## 核心限制（务必了解）

1. **AI 分析为内置规则分析引擎，并非原 LangGraph/LLM 模型。** 可以准确执行支持的统计分析意图，开放式复杂生成/追问能力较弱。UI 不得宣称与原云端大模型完全等价。
2. 商品详情描述保存离线文本；外链和网络图片在飞行模式下不可用。
3. 数据固定为上游发行时的历史快照；不包含平台之后更新。
4. 无互联网权限，不能在线同步、自动更新数据库或调用云端模型。
5. 测试包使用 CI 默认调试签名；不同构建签名可能变化，可能要先卸载旧版。

## CI 编译、校验、发布

修改 \`android-app/**\`、\`tools/**\` 或 workflow 后需触发 GitHub Actions（也支持手动执行）：

[Android APK workflow](../../actions/workflows/android-apk.yml)

构建从 \`https://github.com/Samge0/wzyp-view/releases/download/v1.0.0/wzyp-data.zip\` 下载数据（**仅发生在 CI 编译机**），转换成 \`seed.db\`、将离线页面复制到 Android assets，运行 Gradle 和 apksigner 验证后发布 Release。手机运行阶段不发生任何网络访问。

本地编译前运行：

\`\`\`bash
python3 tools/build_offline_db.py
python3 tools/build_offline_web.py
gradle -p android-app --no-daemon assembleDebug
\`\`\`
