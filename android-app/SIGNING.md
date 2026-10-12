# Android 持久签名配置（v8+）

## 问题原因

旧版本的构建脚本使用 GitHub Actions 临时运行器上的 `assembleDebug` 默认签名，未保留固定的 `debug.keystore`。因此不同构建的签名证书可能不同，Android 会阻止同包名 APK 覆盖安装。单纯提高 `versionCode` 不能解决签名不一致。

## 新方案：由你独占的私有签名，CI 禁止自动重新生成

新版执行 `assembleRelease`，从 **GitHub Actions 仓库 Secrets** 中载入同一把 PKCS12 私钥。若缺少密钥、密码或最终证书指纹不匹配，**构建会失败并阻止发布**，避免再次把不同签名的安装包发给用户。

固定公开证书 SHA-256：

```
D1:54:7A:5A:AE:A2:02:C1:81:1B:47:59:0B:F8:D6:CC:8A:5D:FA:A7:DE:0B:79:A2:98:78:C4:70:D4:91:4B:E8
```

仅**证书指纹**可公开；`.p12` 私钥和密码必须保密。Android 证书私钥不可从 APK、SHA256 指纹或签名证书中恢复。

## 一次性配置 GitHub Secrets

从这次 ChatGPT 对话下载私有签名工具包 `wzyp-v8-private-signing-kit.zip`，只在可信设备解压，**千万不要提交进公共仓库或公开传输**。它包含：

- `wzyp-release.p12`：唯一签名私钥，需要安全离线备份。
- `signing-password.txt`：密码。
- `keystore-base64.txt`：私钥文件 Base64，便于粘贴到 GitHub Secrets。
- `configure-github.sh`：可选，在已登录 GitHub CLI 的电脑上运行，自动设置两个 Secrets。

在 [仓库 Actions Secrets 设置页面](https://github.com/qwer654/wzyp-view/settings/secrets/actions)，新建两项：

| Secret 名 | Secret 值 |
| --- | --- |
| `WZYP_SIGNING_KEYSTORE_B64` | 整行粘贴 `keystore-base64.txt` |
| `WZYP_SIGNING_PASSWORD` | `signing-password.txt` 内容 |

**不能只上传 `.p12` 文件到仓库**，因为仓库公开，泄露签名私钥后别人可仿冒应用发布升级包。

随后打开 [Android APK 工作流](https://github.com/qwer654/wzyp-view/actions/workflows/android-apk.yml)，点击 **Run workflow**，通过后在 Releases 下载 `wzyp-view-android-v8.0.0-stable-signed.apk`。

如在自己的电脑已有 GitHub CLI，解压后执行 `bash configure-github.sh` 即可完成 Secrets 配置。

## 旧版与新版安装限制

- Android 会验证已安装包的签名；**无法使用新签名直接覆盖旧版 v1–v7 调试包**。只有旧版原始私钥可实现真正的原位覆盖，但早期 CI 未保留它。
- 因此必须从旧版先**导出聊天、配置和需要保留的数据**；确认完成后卸载旧版，首次安装 v8 稳定签名 APK。
- 从 **v8 起使用同一套私有证书**，以后 v9、v10 增大 `versionCode`，即可按通常方式覆盖升级（前提是没有更改签名/包名）。
- 数据库（包括手机增量更新所得记录）存放在 Android 应用私有目录，卸载会删除。**从旧的 CI 调试版迁移时**，可先通过电脑 USB 调试与 Android platform-tools 使用仓库脚本 `bash tools/backup_android_debug_db.sh`，它通过 `adb run-as` 读取旧版的 `databases/wzyp_snapshot_v1.db`，导出到本地 `wzyp-android-backup/` 并检查 SQLite 完整性。脚本不会卸载或修改旧应用。若旧版禁止 `run-as`，脚本会直接退出，不保证适用于任意安装包。备份数据库随后可通过新 APK 的“从手机导入 SQLite”恢复。
- 聊天 JSON 请使用旧版的分享/导出功能额外保存。API Key 在 Android Keystore 加密保存，卸载后通常需要重新填写；不要把 API Key 当作可安全从旧 APK 直接迁移的内容。
- 如果用户已经有**旧 APK 的实际签名私钥**，请改用它设置 Secrets，并相应调整锁定的证书指纹；这样有可能保留原位升级，无需卸载。

## 本地签名

在本地提供两个环境变量后可运行 `gradle -p android-app assembleRelease`：

```bash
export WZYP_SIGNING_KEYSTORE_PATH=/secure/path/wzyp-release.p12
export WZYP_SIGNING_PASSWORD='<stored securely>'
gradle -p android-app assembleRelease
```

`WZYP_SIGNING_PASSWORD` 不要明文写入仓库、构建日志或 GitHub workflow 文件。
