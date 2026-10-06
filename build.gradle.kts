// Phone-transfer 根构建脚本
// 版本集中在 gradle/libs.versions.toml，升级工具链只需改那一处。
plugins {
    // 只声明 AGP：Kotlin 支持已由 AGP 9 内置，无需再声明 Kotlin 插件。
    alias(libs.plugins.android.application) apply false
}
