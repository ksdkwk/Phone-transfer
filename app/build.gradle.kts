plugins {
    // AGP 9.0 起内置 Kotlin 支持：再应用 org.jetbrains.kotlin.android 会直接报
    // "The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0".
    // Kotlin 编译器（KGP 2.2.10）与 kotlin-stdlib 由 AGP 自身传递引入。
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.phonetransfer.app"
    // 本机已安装的 Android SDK Platform 17（API 37.0）；改动需同步更新 gradle.properties 中的
    // android.suppressUnsupportedCompileSdk。
    compileSdk = 37

    defaultConfig {
        applicationId = "com.phonetransfer.app"
        minSdk = 26          // 兼容下限：Android 8.0（可行性文档 §2.3）
        targetSdk = 37       // 对齐 Android 17
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // V1 阶段先不开混淆：协议层需要可读日志与可审计的迁移报告（协议 §2）
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)

    testImplementation(libs.junit)
}
