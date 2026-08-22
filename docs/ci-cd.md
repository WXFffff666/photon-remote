# CI/CD 使用说明

## 工作流总览

| 工作流 | 文件 | 触发 | 产物 | 权限 |
|--------|------|------|------|------|
| CI | `.github/workflows/ci.yml` | `push: master` + `pull_request: master` | `PhotonRemote-debug|release-v*.apk` + `test/lint 报告` | `contents: read` |
| Release | `.github/workflows/release.yml` | `push: tags v*.*.*` + `workflow_dispatch` | GitHub Release + `PhotonRemote-*.apk` 资产 | `contents: write` |
| Dependabot | `.github/dependabot.yml` | 每周一 03:00 | PR 自动提依赖更新 | — |

## 提交即构建（CI）

```bash
git add .
git commit -m "feat: xxx"
git push origin master
# 60s 内触发
gh run list --workflow=ci.yml --limit 1
gh run view <run-id> --log | grep PhotonRemote
gh run download <run-id> -n apk-<sha> --dir /tmp/ci-apk && ls /tmp/ci-apk
```

- **并发**：`ci-${{ github.ref }}` + `cancel-in-progress: true`，密集 push 自动取消旧运行。
- **产物**：`actions/upload-artifact@v4` 上传 `app/build/outputs/apk/**/PhotonRemote-*.apk`（保留 14 天）与 `build/reports`。
- **质量门**：`lintDebug` + `testDebugUnitTest` + 双 `assemble` 均成功才算通过。

## 发布 Release（tag 触发）

```bash
# 1) 先改版本（必须与 tag 一致）
# 编辑 app/build.gradle.kts: versionName = "1.0.1" + versionCode = 2
# 2) 提交并打 tag
git add app/build.gradle.kts
git commit -m "chore: bump version to 1.0.1"
git tag v1.0.1
git push origin master --follow-tags   # 或 git push origin v1.0.1
# 3) 观察
gh run list --workflow=release.yml --limit 1
gh release view v1.0.1 --json tagName,assets | jq .assets[].name
curl -I https://github.com/WXFffff666/photon-remote/releases/download/v1.0.1/PhotonRemote-release-v1.0.1.apk
```

- **tag 校验**：`release.yml` 校验 `tag == v<versionName>`，不符直接失败（防止误发）。
- **签名**：见 `docs/ci-signing.md` 配置 4 个 secrets；未配则产 debug 签名包（不可分发）。
- **幂等/并发**：`release-${{ github.ref }}` + `gh release view` 检查已存在则更新资产；重复 push 同 tag 旧运行被取消。
- **手动触发**：Actions 页 → Release → Run workflow → 输入 tag（如 `v1.0.1`）。

## 回滚

```bash
gh release delete v1.0.1 --yes --repo WXFffff666/photon-remote
git push --delete origin v1.0.1
git tag -d v1.0.1
# 修复后重打
git tag v1.0.1 && git push origin v1.0.1
```

## 本地冒烟（与 CI 一致）

```powershell
tools\smoke.bat
# 等价于 CI 的：lintDebug + testDebugUnitTest + assembleDebug + assembleRelease
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug :app:assembleRelease
ls app/build/outputs/apk/debug/PhotonRemote-debug-v*.apk
ls app/build/outputs/apk/release/PhotonRemote-release-v*.apk
```

## 产物命名

`app/build.gradle.kts: applicationVariants` 统一产出 `PhotonRemote-<buildType>-v<versionName>.apk`，CI 与本地一致，下载后可 `sha256sum` 对比本地与 CI 产物。

## 排障

- `gh workflow view ci --yaml` / `gh workflow view release --yaml` 查看生效配置
- `grep -R storePassword .github` 应无明文（仅 `secrets.` 引用）
- `git ls-files | grep -E "jks|keystore"` 应为空（密钥不入库）
