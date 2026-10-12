# wzyp-view Android 客户端

这是原项目 FastAPI + MySQL + LangGraph 系统的 **Android WebView 客户端**，不是 Python/MySQL/LLM 的离线移植。Android 8.0+ 可安装。

## 能做什么

- 首页数据总览、店铺与商品查询、详情、AI 聊天及 Web 设置页沿用原项目能力。
- 适配 360px 等窄屏：水平滑动表格、紧凑导航、手机聊天对话列表。
- 首次打开填写服务器根地址，保存在应用私有偏好设置。右上角“服务器”随时切换。
- JavaScript/DOM Storage、文件选择、普通 HTTP(S) 下载、网页外链跳转到系统浏览器。
- 公网仅接受 HTTPS 服务地址；HTTP 限局域网私有地址。拒绝跨站混合内容与不安全证书例外。

## 部署服务端

仓库根目录运行以下命令（先按主 README 准备 MySQL 数据库和依赖）：

```bash
# Linux/macOS；服务端需处于可信局域网或 VPN 中
WZYP_HOST=0.0.0.0 WZYP_PORT=8770 python run.py
```

手机和电脑连接同一 Wi-Fi，查看电脑的局域网 IP（例如 `192.168.1.5`），在 APK 的“服务器”页面输入 `http://192.168.1.5:8770`。

**注意：** 手机的 `127.0.0.1` 指手机本身，不是电脑；防火墙需允许指定端口从手机访问。

互联网部署必须使用 HTTPS 反向代理 + 认证/访问控制（建议 VPN 或 Cloudflare Access 等）。**原 FastAPI 项目尚未给设置/查询/聊天等接口加认证，不得裸露到公网。** 不要把 MySQL 密码和 LLM 密钥写入 APK。原网页设置页的配置存在后端而非手机。

## 构建 APK

Github Actions: **Actions → Android APK → Run workflow**，或修改 `android-app/**` 后 push 到 `main` 自动构建。

构建成功后：
- Actions 构建记录的 `wzyp-view-android-debug` artifact 可下载。
- 同时由构建流程发布到本仓库 **Releases**，文件名 `wzyp-view-android-v1.0.0-debug.apk`。

手动本地编译（Android SDK 35 / JDK 17 / Gradle 8.11.1）：

```bash
gradle -p android-app --no-daemon assembleDebug
# android-app/app/build/outputs/apk/debug/app-debug.apk
```

当前工作流生成开发测试签名的 **debug APK**。每次 GitHub Runner 的签名密钥可能不同，升级安装遇到“签名不一致”时需卸载旧版。正式上线必须配置稳定且私密的 release 签名密钥（不能将密钥提交到公开仓库）。

## 已知限制

- 需要能访问运行中的原项目服务端；后端和数据不打包进 APK。
- JS Blob 生成的配置导出文件无法用 Android DownloadManager 下载；可从系统浏览器访问后端导出。
- 会话内容及服务端配置由原 Web/后端处理，切换服务器后数据不会自动迁移。
