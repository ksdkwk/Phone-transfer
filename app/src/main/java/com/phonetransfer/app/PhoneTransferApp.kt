package com.phonetransfer.app

import android.app.Application
import com.phonetransfer.app.core.settings.TransferSettingsStore

/**
 * 应用入口。
 *
 * 启动流程刻意保持极简：**没有闪屏页、没有引导页、没有登录页**，
 * 也**不在启动时申请任何权限**（权限随使用场景按需申请，可行性文档 §6.7）。
 */
class PhoneTransferApp : Application() {

    /** 传输设置（仅策略开关）懒加载，避免启动期做多余 IO。 */
    val transferSettings: TransferSettingsStore by lazy { TransferSettingsStore(this) }
}
