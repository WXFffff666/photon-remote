# 安全与防误报说明（防爆毒）

本文档说明 Photon Remote 的 release 构建安全机制，以及为何杀毒软件可能误报、如何应对。

## 1. Release 包的安全机制

### 1.1 R8 代码混淆（等效替代商业 DEX 加固的基础层）

- `app/build.gradle.kts` 中 release buildType 开启：
  - `isMinifyEnabled = true` — R8 全量模式：移除未使用代码 + 类名/方法名/字段名混淆（`a`/`b`/`c` 式短名），显著提高逆向分析与恶意篡改的成本。
  - `isShrinkResources = true` — 资源收缩：删除未引用的资源，同时缩小包体。
- 关键保留规则（`app/proguard-rules.pro`）：
  - `net.irext.decode.sdk.**` — IREXT 解码器 JNI 绑定，字段由 C 层 `GetFieldID` 按名字访问，混淆会破坏绑定。
  - `com.photon.remote.data.model.**` — kotlinx.serialization 序列化模型，反射读写依赖原始字段名。
  - `-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod` — 注解与泛型签名，Room/序列化运行期校验依赖。
  - Room / DataStore / Compose 自带 consumer 规则，由 R8 自动处理。

### 1.2 APK 签名

- 签名密钥：RSA 2048，alias `photon-remote`，有效期 10000 天，证书 `CN=Photon Remote`（JKS，`D:\Android\keystore\`，**项目目录之外**，不入 git）。
- 签名方案（`apksigner verify` 验证通过）：
  - v2（APK Signature Scheme v2）✅ — Android 7.0+ 全量文件校验，防篡改核心。
  - v3（APK Signature Scheme v3）✅ — Android 9+ 密钥轮换支持。
  - v1（JAR 签名）❌ 未启用 — 遗留弱方案；minSdk 24 起所有目标设备均支持 v2，AGP 8.x 在 minSdk≥24 时自动忽略 v1。
- 校验命令：
  ```powershell
  & "D:\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --verbose --print-certs "app\build\outputs\apk\release\app-release.apk"
  ```

## 2. 为什么没有使用商业 DEX 加固

- 商业加固（如腾讯乐固、360 加固、梆梆等）属于**云服务**：需注册厂商账号、上传 APK 到云端加固、下载加固包，且**重新签名必须使用厂商私钥**（或付费托管密钥），不适用于本开源项目的构建流程。
- 加固 SDK 普遍包含闭源 so 库与加密壳，体积增加数 MB，且部分加固壳在 Android 14+ 存在兼容性风险。
- 本项目采用 **R8 全量混淆 + 资源收缩 + v2/v3 签名** 作为等效替代：
  - 对静态逆向（jadx / dex2jar）的防护效果与商业加固的基础功能（混淆 + 签名校验）相当；
  - 完全开源、可复现构建（`assembleRelease` 一条命令）、无第三方依赖；
  - 若未来有强对抗需求（如防动态调试、防 Hook），可另行评估接入商业加固，届时需要厂商账号并重新生成签名。

## 3. 常见杀毒软件误报说明

### 3.1 为什么可能误报

- **混淆后特征相似**：R8 产生的 `a/b/c` 类名与部分恶意样本的混淆特征相似，启发式引擎可能误判。
- **动态加载 / JNI 原生代码**：本项目 IREXT 解码器使用 JNI（native 层），杀毒引擎对「加载 so + 反射调用」模式敏感，与恶意程序行为特征重叠。
- **新签名证书**：个人签名证书无信誉积累，首次分发容易被引擎按「低信誉 + 高启发式命中」组合判为可疑。
- **打包方式**：未经商店签名渠道分发的 APK 天然触发「未知来源应用」告警。

### 3.2 如何核实与处置

1. **核实签名完整性**：运行上述 `apksigner verify`，确认签名者为 `CN=Photon Remote`、证书 SHA-256 与发布声明一致 —— 签名真实即未被篡改。
2. **上传 VirusTotal 复查**：多个引擎交叉验证；绝大多数误报只有 1-2 个引擎命中（占比 <5 属于典型误报）。
3. **误报反馈**：在对应杀毒厂商官网提交「误报申诉」，提供 APK 下载链接与 `apksigner` 签名输出即可，通常 1-3 个工作日解除。
4. **安装引导**：向用户说明启用「允许安装未知来源应用」前的验证方法（对比证书 SHA-256）。

### 3.3 本项目当前状态

- 混淆：R8 全量开启（`isMinifyEnabled=true` + `isShrinkResources=true`）。
- 签名：v2/v3 双重校验通过，单签名者 RSA 2048。
- 未使用任何第三方加固 SDK；全部构建产物可由源码复现（`gradlew.bat :app:assembleRelease`），任何装机包均可与本地构建比对 SHA-256 验证来源。
