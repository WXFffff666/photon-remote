# Photon Remote（光子遥控）

> 一款安卓万能红外遥控器 App。把电视、机顶盒、空调、风扇等家电装进你的手机里，中国市场优先，界面现代、简洁、快速上手。

<div align="center">

[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-7.0%2B-brightgreen.svg)](app/build.gradle.kts)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.20-purple.svg)](gradle/libs.versions.toml)
[![Compose](https://img.shields.io/badge/UI-Compose%20M3-blue.svg)](gradle/libs.versions.toml)
[![CI](https://github.com/WXFffff666/photon-remote/actions/workflows/ci.yml/badge.svg)](https://github.com/WXFffff666/photon-remote/actions/workflows/ci.yml)
[![Release](https://github.com/WXFffff666/photon-remote/actions/workflows/release.yml/badge.svg)](https://github.com/WXFffff666/photon-remote/actions/workflows/release.yml)

</div>

---

## 目录

- [功能特性](#功能特性)
- [技术栈](#技术栈)
- [截图](#截图)
- [开发状态](#开发状态)
- [快速上手 / 演示指南](#快速上手--演示指南)
- [构建说明](#构建说明)
- [架构简述](#架构简述)
- [码库与许可声明](#码库与许可声明)
- [许可证](#许可证)
- [免责声明](#免责声明)

---

## 功能特性

### P0 · 核心功能

- **红外检测与发射**：启动即检测设备红外能力（`ConsumerIrManager`），无红外手机可降级走 USB 或音频路径，绝不崩溃。
- **多路径自动路由**：发射路径按「USB 红外 > 内置红外 > 音频转红外」自动选择，也可在设置中手动指定。
- **8 类设备分类**：电视、机顶盒、空调、风扇、投影仪、音响、净化器、其他。
- **添加设备向导**：分步引导「设备类型 → 品牌 → 型号/码组 → 一键测试 → 命名保存」。
- **机顶盒省市区运营商三级筛选**：省份 → 城市 → 运营商（中国移动 / 中国联通 / 中国电信 / 中国广电），数据来自 IREXT 索引。
- **遥控器主界面**：醒目电源键、中央方向键（D-pad）、音量/频道列、可折叠展开的数字键盘、返回/菜单/输入源按键。
- **按压反馈**：按键按压动画、轻震动反馈、长按连发（NEC 家族与 JVC 按协议短重复帧节奏，其余按 250ms）。
- **14 种红外协议编码器**：NEC / NECx1 / NECx2 / RC5 / RC6 / SONY12 / SONY15 / SONY20 / SAMSUNG32 / SHARP / JVC / KASEIKYO / PIONEER / RAW，每种协议均有完整波形单元测试（前导、位序、帧尾、总长）。
- **IREXT 离线码库**：JNI 解码，覆盖国内机顶盒、空调等全品类二进制码库，完全离线内置，不依赖网络。
- **自定义布局编辑**：6×8 网格画布，长按拖拽移动、缩放（colSpan/rowSpan）、圆形与圆角矩形切换、从按键抽屉添加按键，保存为自定义布局。
- **主题**：Material You 动态取色（Android 12+）、深浅色切换、自定义强调色（8 选 1）。

### P1 · 进阶功能

- **空调控制面板**：大温度数字 + −/+ 步进、制冷/制热/自动/送风/除湿模式、风速、扫风开关；温度范围、模式、风速、扫风能力从码库动态读取，AC 状态重启后自动恢复。
- **宏**：把多个设备的多步按键串成一条宏，顺序执行、可调步间延迟、可中途停止。
- **暴力找码**：协议 + hex 前缀约束，自动迭代发码，设备响应即保存为按键。
- **导入导出**：
  - 导入 Flipper `.ir` 文件（raw 与 parsed 两种类型）；
  - 导入 LIRC `.conf` 文件（按键名与协议自动映射）；
  - 导出 / 导入 JSON 全量备份（设备 + 按键 + 宏），逐记录校验，坏记录跳过不中断。
- **无红外扩展**：
  - USB 红外外设（VID `0x10C4` / `0x045E`，PID `0x8468`，RLE 帧收发，热插拔重连）；
  - 音频转红外适配器（AudioTrack 192kHz 合成 38kHz 载波，96kHz 回退，支持 mono 1-LED 与 stereo anti-phase 2-LED）。
- **发送调度器**：所有红外发送与 IREXT 解码经单线程队列串行执行，杜绝并发错码，NEC 长帧不阻塞主线程。

### P2 · 锦上添花

- **收藏**：设备收藏置顶分组显示，排序可拖拽并持久化。
- **纯黑 AMOLED 主题**：暗色模式下可开启纯黑变体。
- **桌面快捷方式**：长按设备卡片「添加到桌面」，一键直达遥控器页（ShortcutManagerCompat，兼容 API 24+）。
- **平板双栏适配**：≥840dp 宽屏下首页左右分栏（左列表右遥控器），遥控器居中 640dp 不拉伸；手机/平板自适应 WindowSizeClass。

---

## 技术栈

| 层面 | 技术 | 版本 / 说明 |
|---|---|---|
| 语言 | Kotlin | 2.1.20 |
| UI | Jetpack Compose + Material 3 | BOM 2025.06.01，动态取色 + 深浅色 + 纯黑 + 强调色 |
| 自适应 | material3-adaptive-navigation-suite / adaptive | 手机 Compact 与平板 Expanded 双形态 |
| 数据库 | Room | 2.7.1，设备 / 按键 / 宏三表 |
| 设置存储 | DataStore Preferences | 1.1.4 |
| 序列化 | kotlinx.serialization | 1.8.1（按钮动作 / 布局 / 备份） |
| 导航 | Navigation Compose | 2.9.0 |
| 内置红外 | android.hardware.ConsumerIrManager | 系统红外发射 |
| 码库解码 | IREXT | MIT，JNI 离线解码二进制码库 |
| 码库数据 | irdb | 宽松许可，精选 CSV 离线打包 |
| 构建 | AGP / Gradle wrapper | 8.9.1 / 8.14 |
| 测试 | JUnit 4 + Mockito + Robolectric | 协议波形、仓储、调度器、序列化全覆盖 |

> 版本以 `gradle/libs.versions.toml` 与 `app/build.gradle.kts` 为唯一来源。

---

## 截图

> UI 正在开发中，以下为预留截图位，界面完成后将在此补充。

![遥控器主界面](docs/screenshots/remote-screen.png)

<!-- TODO: UI 完成后替换为真实截图：遥控器主界面（电源键/方向键/数字键盘） -->

![添加设备向导](docs/screenshots/add-device.png)

<!-- TODO: UI 完成后替换为真实截图：添加设备向导（设备类型/品牌/省市运营商/型号测试） -->

![空调控制面板](docs/screenshots/ac-panel.png)

<!-- TODO: UI 完成后替换为真实截图：空调面板（温度/模式/风速） -->

![首页设备列表](docs/screenshots/home-screen.png)

<!-- TODO: UI 完成后替换为真实截图：首页设备卡片网格 -->

---

## 开发状态

| 模块 | 状态 |
|---|---|
| 工程骨架（Gradle / Manifest / 主题 / 导航） | ✅ 已完成 |
| 数据层（Room 三表 / DataStore / Repository / 序列化） | ✅ 已完成 |
| 红外核心（14 种协议编码器 / 内置 / USB / 音频发射器 / 调度器） | ✅ 已完成 |
| IREXT JNI 解码桥 + 空调状态机 | ✅ 已完成 |
| 离线码库资产（IREXT 索引 + 二进制 1.76MB + irdb 精选 CSV） | ✅ 已完成 |
| 单元测试 | ✅ 211 个全部通过 |
| UI 页面（首页 / 向导 / 遥控器 / 空调 / 宏 / 设置等） | ✅ 已完成 |

---

## 快速上手 / 演示指南

> 3 步即可完成一次演示：**添加电视 → 测试发码 → 宏联动**。新装机 2 分钟内可走完前两步；本应用**未预置演示设备**（尊重真实使用习惯，避免误导），演示前现场添加即可，全程离线可用。

### 第 1 步：添加电视（≤2 分钟）

1. 首页点右下角 **「＋ 添加遥控器」**（首次启动为空状态引导页，同样入口）；
2. 向导第 1 步选 **「电视」** → 第 2 步选品牌（如「小米」「海信」等，支持搜索框过滤）；
3. 第 3 步在型号/码组列表选一个，进入 **测试页**；
4. 点击大 **「电源」测试键**，对着电视接收窗观察是否响应——响应即点「保存并命名」，不响应则点「换一个码组」重试；
5. 保存后首页出现设备卡片，点击进入遥控器页。

### 第 2 步：测试发码

- 遥控器页自上而下：**电源键 / 方向键区（上·下·左·右·确定）/ 音量·频道列 / 数字键盘**；
- 按任意键：按键有按压动画 + 轻震动，发送成功顶部提示"已发送"；无可用发射器时按键禁用并提示（可到 **设置 → 发射路径** 切换 USB / 音频转红外）；
- 长按音量/频道键连发；**数字键盘**点「123」展开 0-9。

### 第 3 步：宏联动（进阶演示）

1. 底部导航进入 **「宏」** → 新建；
2. 依次选择设备与按键（如「电视·电源」→「电视·音量+」→「机顶盒·确定」），每步可调延迟（默认 300ms）；
3. 保存后点 **播放**：宏按序执行，当前步骤高亮，可中途停止。

### 演示小贴士

- **无红外手机也能演示**：插入 USB 红外 dongle（设置 → 发射路径 → USB 外设），或插入音频转红外 LED 适配器（音量开最大，设置 → 音频转红外模式）。
- **码不匹配怎么办**：测试页「换一个码组」，或走 **「暴力找码」**（设置协议 + 前缀自动试码），或**导入** Flipper .ir / LIRC .conf / JSON 备份。
- 全部功能离线可用（码库内置），**飞行模式**下演示不受影响。

---

## 构建说明

### 环境要求

| 项 | 要求 |
|---|---|
| JDK | 17 |
| Android SDK | 36（compileSdk = 36） |
| Gradle | 8.14（wrapper 已内置，无需单独安装） |
| 设备 | minSdk 24（Android 7.0），targetSdk 35 |

### 构建命令

Windows 使用 `gradlew.bat`，macOS / Linux 使用 `./gradlew`：

```bash
# 调试包（可直接安装调试）
gradlew.bat assembleDebug

# 发布包（R8 混淆 + 资源压缩）
gradlew.bat assembleRelease

# 单元测试（协议波形 / 数据层 / 调度器 / 序列化）
gradlew.bat testDebugUnitTest

# 一键冒烟：assembleDebug + testDebugUnitTest（Windows）
tools\smoke.bat
```

### CI/CD（提交即构建，tag 即发布）

- **CI**：`push → master` 与 `pull_request → master` 自动触发 `.github/workflows/ci.yml`，执行 `lintDebug + testDebugUnitTest + assembleDebug + assembleRelease`，上传 `PhotonRemote-*.apk` 与测试/Lint 报告（见 `docs/ci-cd.md`）。
- **Release**：`git tag v*.*.* && git push origin v*.*.*` 触发 `.github/workflows/release.yml`，校验 `tag == v<versionName>`，签名后创建 GitHub Release 并上传双 APK（签名配置见 `docs/ci-signing.md`）。
- **依赖更新**：`.github/dependabot.yml` 每周一自动提 Gradle 与 GitHub Actions 更新 PR。

---



## 架构简述

单模块 + 简单 MVVM + Repository + 手动 DI（无 Hilt），依赖方向单向：UI → Repository → 数据层 / 红外层。

```
app/src/main/java/com/photon/remote/
├── PhotonApplication.kt        # Application：手动 DI 容器 + 码库初始化
├── MainActivity.kt             # 单 Activity，Compose 入口
├── data/                       # 数据层
│   ├── local/                  #   Room（DeviceDao/ButtonDao/MacroDao）+ DataStore 设置
│   ├── local/entity/           #   设备 / 遥控器按键 / 宏 三张表实体
│   ├── model/                  #   设备类型 / 运营商 / 按钮动作 / AC 状态等模型
│   └── repository/             #   统一仓储（设备 + 按键 + 宏 + 收藏排序）
├── ir/                         # 红外层
│   ├── core/                   #   IRPattern / 协议枚举 / 编码器接口
│   ├── protocol/               #   14 种协议编码器（每协议一个文件）
│   ├── transmitter/            #   内置 / USB / 音频发射器 + 路由 + 调度器
│   └── irext/                  #   IREXT JNI 解码桥 + 空调状态机
├── codebase/                   # 码库层（开发中）
│   ├── importer/               #   Flipper .ir / LIRC .conf / JSON 备份
│   └── finder/                 #   暴力找码引擎
├── ui/                         # UI 层（页面开发中）
│   ├── theme/                  #   动态取色 / 深浅色 / 纯黑 / 强调色
│   ├── navigation/             #   导航骨架（自适应 NavigationSuite）
│   └── ...                     #   首页 / 向导 / 遥控器 / 空调 / 宏 / 设置
└── viewmodel/                  # 页面 ViewModel（开发中）
```

**关键设计**：

- **发射链路**：`CodeResolver` 是「按钮动作 → 红外波形」的唯一入口；所有发送与 IREXT 解码经 `IrDispatcher` 单线程队列串行，页面会话与一次性调用（宏 / 暴力找码）互不干扰。
- **会话守卫**：IREXT 二进制按需打开，进入遥控器页打开一次、退出关闭；一次性的 open/decode/close 自包含，且无论成败都会恢复先前的打开会话。
- **容错**：x86 设备无 IREXT so 时自动降级为协议编码器直发，绝不崩溃。

---

## 码库与许可声明

本应用内置了三套第三方红外码库/解码组件，在此一并声明出处与许可。

### irdb 出处声明（必须保留）

> Contains/accesses irdb by Simon Peter and contributors, used under permission. For licensing details, see: <https://github.com/probonopd/irdb>

- 本应用从 irdb 精选了中国常见品牌的 CSV 码库，离线打包于 `assets/irdb/`，仅用于本应用内部发射。
- 按 irdb 的分发约定，本项目在「关于」页与本文档中声明出处；如作者按条款请求，本项目将提供 3（三）份免费副本。
- 详细说明见 [docs/irdb-license-notice.md](docs/irdb-license-notice.md)。

### IREXT 致谢

码库解码基于 [IREXT](https://github.com/irext/irext-core) 的开源实现（MIT 许可）。本项目按原样使用其二进制码库与 JNI 解码包装类（`net.irext.decode.sdk`），保留原始版权注释。

### Flipper IRDB 致谢

导入能力兼容 Flipper `.ir` 文件格式。[Flipper IRDB](https://github.com/Lucaslhm/Flipper-IRDB)（MIT 许可）感谢其社区维护的丰富码库生态。

### 合规说明

- 本项目自身体例为 **MIT**，不含任何 GPL / AGPL 代码或数据。
- **mi_remote_database（AGPLv3）未被本项目使用**，明确排除。

---

## 许可证

本项目以 [MIT License](LICENSE) 发布，Copyright (c) 2026 Photon Remote contributors。

---

## 免责声明

- 红外码库数据来源于社区收集（irdb / IREXT），不同批次、不同型号的设备可能存在差异，个别按键可能无法匹配。请以「添加向导」中的实测为准。
- 如遇码库不匹配，可使用**自定义按键**（手动添加码值）或**暴力找码**功能自行匹配。
- 使用本项目代替遥控器操作设备时，请遵守所在地对无线设备使用的相关规定，因误操作造成的后果由使用者自行承担。
