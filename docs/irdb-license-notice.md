# irdb 码库许可说明

> 本文档是 [Photon Remote](../README.md) 对内置 irdb 码库的许可与出处说明，对应 README 中的「irdb 出处声明」。应用内「关于」页同样保留本说明。

---

## 1. 出处

- **项目名称**：irdb（InfraRed Database，红外码数据库）
- **作者**：Simon Peter 及贡献者
- **上游仓库**：<https://github.com/probonopd/irdb>

irdb 是一个社区维护的红外遥控码数据库，收录了大量品牌电视、机顶盒、音响、投影仪等设备的红外遥控码，长期被各类开源与商业遥控软件参考使用。

---

## 2. 使用方式

Photon Remote 从 irdb 的 `codes/` 目录中**精选中国常见品牌**（小米、华为、创维、TCL、海信、康佳、长虹、海尔、格力、美的、三星、LG、索尼、夏普、松下、东芝、飞利浦、乐视、中兴等，以实际打包目录为准），处理为：

```
app/src/main/assets/irdb/<品牌>/<设备类型>/<型号>.csv
```

- CSV 保持 irdb 原始格式：`functionname,protocol,device,subdevice,function`；
- 随 APK **离线打包**，供应用内「添加设备向导」与遥控器发码使用；
- 数据**仅用于本应用内部发射**，不对外单独分发，不提取为独立数据集。

---

## 3. 许可声明

按 irdb 的要求，本应用必须保留以下出处声明：

> **Contains/accesses irdb by Simon Peter and contributors, used under permission. For licensing details, see: <https://github.com/probonopd/irdb>**

该声明同时出现于：

- 本仓库根目录 `README.md`；
- 应用内「设置 → 关于」页。

---

## 4. 分发条款（按需提供 3 份免费副本）

按 irdb 的分发约定，任何再分发包含 irdb 数据的作品，应可应作者要求提供免费副本。本项目承诺：

1. 本项目通过应用商店等渠道分发时，对用户不构成销售行为（免费应用），但仍**保留并尊重**该条款；
2. 若作者（Simon Peter）或其授权代表就本项目提出请求，本项目将按约定提供 **3（三）份免费副本**；
3. 若使用者自行再分发本应用或其中携带的 irdb 数据，请自行遵守同样条款，并保留本声明。

---

## 5. 合规说明

- irdb 采用**宽松许可**，本项目已按要求在 README 与「关于」页完整声明出处；
- 本项目自身体例为 **MIT**（见根目录 [LICENSE](../LICENSE)），**不含任何 GPL / AGPL 代码或数据**；
- **mi_remote_database（AGPLv3）未在本项目中使用**，明确排除。

---

## 6. 相关链接

| 组件 | 链接 | 许可 |
|---|---|---|
| irdb | <https://github.com/probonopd/irdb> | 宽松许可（需声明，见上文） |
| IREXT | <https://github.com/irext/irext-core> | MIT |
| Flipper IRDB | <https://github.com/Lucaslhm/Flipper-IRDB> | MIT |

---

*最后更新：2026 年。若上游 irdb 许可条款发生变更，请以 [github.com/probonopd/irdb](https://github.com/probonopd/irdb) 为准并及时更新本文档。*
