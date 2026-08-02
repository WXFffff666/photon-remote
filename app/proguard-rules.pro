# ProGuard / R8 规则
# IREXT JNI 保留规则：IRDecode 的 native 方法通过 JNI 反射绑定，字段由 C 层
# GetFieldID 按名字访问（acPower/acMode/acTemp 等），混淆会破坏绑定，必须整体保留
-keep class net.irext.decode.sdk.** { *; }
