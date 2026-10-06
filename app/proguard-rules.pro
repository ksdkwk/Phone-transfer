# Phone-transfer 混淆规则
# V1 阶段保持 isMinifyEnabled = false；启用混淆时需保留协议层的常量与枚举名称，
# 以便日志与迁移报告可读（协议规范 §2「可审计」）。
-keepclassmembers enum com.phonetransfer.app.core.protocol.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep class com.phonetransfer.app.core.protocol.** { *; }
