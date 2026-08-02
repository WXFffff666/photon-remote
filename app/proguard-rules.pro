# ProGuard / R8 规则（release 构建：isMinifyEnabled=true + isShrinkResources=true）
#
# 说明：
# - Room / DataStore / kotlinx.serialization / Compose 均自带 consumer 规则，
#   R8 会依据注解自动保留所需成员，无需手写额外 keep（仅序列化模型需显式保留）。
# - 混淆后 class 名被重命名为 a/b/c，可显著提高逆向成本，配合资源收缩缩小包体，
#   等效替代商业 DEX 加固的基础防护（详见 docs/security.md）。

# IREXT JNI 保留规则：IRDecode 的 native 方法通过 JNI 反射绑定，字段由 C 层
# GetFieldID 按名字访问（acPower/acMode/acTemp 等），混淆会破坏绑定，必须整体保留
-keep class net.irext.decode.sdk.** { *; }

# 序列化模型保留：kotlinx.serialization 通过反射读写字段，混淆字段名会导致序列化错乱
-keep class com.photon.remote.data.model.** { *; }

# 图标说明（Todo 40 检查结论）：UI 图标均为代码内静态 ImageVector 引用
# （DeviceType.icon() / Icons.Rounded.*），不存在"字符串键名 → 图标"的运行时映射，
# RemoteButton.icon 字段当前未参与渲染，因此无需 IconMap 类 keep 规则；
# 若未来引入字符串图标映射，需在此补充对应的 keep 或映射表防裁剪。

# 通用保留属性：注解（Room/序列化校验依赖）、泛型签名、内部类/封闭方法结构
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# 保留崩溃堆栈行号（release 便于线上问题定位；若追求极致体积可移除）
-keepattributes SourceFile, LineNumberTable
