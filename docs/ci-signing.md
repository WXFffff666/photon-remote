# CI 签名配置指南

> 适用：`app/build.gradle.kts` 的 `signingConfigs.release` 与 `.github/workflows/release.yml`

## 1. 原理

- `app/build.gradle.kts` 已兼容多路径候选（按优先级）：
  1. `D:/Android/keystore/keystore.properties` — 本地 Windows 历史路径
  2. `$rootDir/keystore.properties`
  3. `$projectDir/keystore.properties`（即 `app/keystore.properties`）
  4. `/tmp/keystore.properties` / `/tmp/keystore/keystore.properties`
- `release.yml` 在 CI 中用 `secrets.KEYSTORE_BASE64` 解码出 ` /tmp/photon-remote.jks` 并向上述全部候选路径写入 `keystore.properties`，因此无论哪条分支命中都能签名成功。
- 若所有候选都不存在，`assembleRelease` 会回退 `debug` 签名并 `logger.warn("release keystore 缺失…")`，**禁止分发**。

## 2. 本地（Windows）

```powershell
# 1) 准备目录
mkdir D:\Android\keystore -Force
# 2) 创建 keystore.properties（示例）
@"
storeFile=D:/Android/keystore/photon-remote.jks
storePassword=你的store密码
keyAlias=photon
keyPassword=你的key密码
"@ | Set-Content D:\Android\keystore\keystore.properties -Encoding UTF8
# 3) 将 .jks 放到 storeFile 指向的位置
Copy-Item photon-remote.jks D:\Android\keystore\photon-remote.jks
# 4) 验证
.\gradlew.bat :app:assembleRelease
# 5) 校验签名（build-tools 36.0.0）
D:\Android\Sdk\build-tools\36.0.0\apksigner.bat verify --verbose --print-certs app\build\outputs\apk\release\PhotonRemote-release-v1.0.0.apk
```

> `storeFile` 支持绝对或相对路径；若写相对路径，会以 `keystore.properties` 所在目录为基准解析（见 `app/build.gradle.kts` 注释）。

## 3. CI（GitHub Actions）— 4 个 Secrets

在 `https://github.com/WXFffff666/photon-remote/settings/secrets/actions` 新建：

| Secret | 值 | 如何生成 |
|--------|----|----------|
| `KEYSTORE_BASE64` | `.jks` 的 base64（单行） | `base64 -w0 D:/Android/keystore/photon-remote.jks`（Git Bash）或 `certutil -encode` 后去换行 |
| `KEYSTORE_PASSWORD` | storePassword | 同 `keystore.properties` |
| `KEY_ALIAS` | keyAlias | 同上 |
| `KEY_PASSWORD` | keyPassword | 同上 |

可选：`GRADLE_ENCRYPTION_KEY`（由 `gradle/actions/setup-gradle@v4` 自动建议，用于缓存加密，缺省也可）。

```bash
# 生成（Linux/macOS/WSL）
base64 -w0 D:/Android/keystore/photon-remote.jks > /tmp/ks.b64
cat /tmp/ks.b64  # 复制粘贴到 GitHub Secret

# GitHub CLI 批量设置（需 gh auth login）
gh secret set KEYSTORE_BASE64 --body "$(cat /tmp/ks.b64)" --repo WXFffff666/photon-remote
gh secret set KEYSTORE_PASSWORD --body "你的store密码" --repo WXFffff666/photon-remote
gh secret set KEY_ALIAS --body "photon" --repo WXFffff666/photon-remote
gh secret set KEY_PASSWORD --body "你的key密码" --repo WXFffff666/photon-remote
```

> `release.yml` 会 `echo "::add-mask::"` 屏蔽密码，日志中不会泄露；并校验 `apksigner verify` 且拒绝 debug 证书（若 secrets 已设但仍为 debug 签名则直接失败）。

## 4. 常见坑

- **tag 与 versionName 不一致**：`release.yml` 会 `grep versionName` 与 `tag` 严格校验 `tag == v<versionName>`，不一致直接 `exit 1`（避免发错版）。
- **回退静默**：未配 secrets 时 `assembleRelease` 仍能产包但为 debug 签名，`release.yml` 会告警并在有 secrets 却仍 debug 时失败，本地请用 `apksigner verify` 自检。
- **产物名**：`PhotonRemote-release-v1.0.0.apk` 由 `applicationVariants` 生成，CI 用 `PhotonRemote-release-v*.apk` 通配上传，不用旧名 `app-release.apk`。
