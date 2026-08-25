# irext-binaries.zip（不入库，下载优先）

本目录不再内置二进制码库 zip（历史占位文件已移除）。

- 索引文件 `irext-index.json` 随仓库分发，用于品牌/型号浏览与分类元数据。
- 二进制码组在需要时由「设置 → 码库更新」（CodebaseUpdater）从 GitHub Release
  在线下载：全量/增量包均带逐文件 SHA-256 校验与失败回滚，落盘 `filesDir/codedb/`。
- 官方源：<https://github.com/irext>（IREXT，MIT）；镜像：本项目 Release 附件。

缺失该 zip 时应用正常启动：相关码组按键提示不可用并告警一次，不会崩溃。
