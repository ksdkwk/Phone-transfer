package com.phonetransfer.app.core.permission

import android.Manifest
import android.os.Build
import com.phonetransfer.app.core.protocol.ItemType

/**
 * 单个数据项的权限计划。
 *
 * 关键合规约束（可行性文档 §7.3、协议 §11）：
 * 未勾选的数据项**不得**申请对应权限，因此权限申请发生在数据项选择之后，
 * 由本计划驱动，而不是在启动时统一索取。
 *
 * @property readPermissions 旧机（发送端）读取该数据项所需权限
 * @property writePermissions 新机（接收端）写入该数据项所需权限
 * @property requiresSeparateConsent L4 极高敏感项需要单独同意，不并入主同意
 * @property requiresDefaultSmsApp 需要临时成为默认短信应用（V1.1）
 * @property requiresUserSelectedDirectory 需要用户经 SAF 授权具体目录（不申请所有文件访问）
 */
data class ItemPermissionPlan(
    val itemType: ItemType,
    val readPermissions: List<String>,
    val writePermissions: List<String>,
    val requiresSeparateConsent: Boolean = false,
    val requiresDefaultSmsApp: Boolean = false,
    val requiresUserSelectedDirectory: Boolean = false,
)

/**
 * 数据项 → 权限的映射表（Android 侧）。
 *
 * 设计取舍：不申请 MANAGE_EXTERNAL_STORAGE（所有文件访问），文档类一律走 SAF 用户授权目录，
 * 以规避商店政策风险（可行性文档待决策项 D3 建议）。
 */
object PermissionCatalog {

    /** Android 13（API 33）起改用细分媒体权限。 */
    private fun imageVideoReadPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun audioReadPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun planOf(itemType: ItemType): ItemPermissionPlan = when (itemType) {
        ItemType.CONTACT -> ItemPermissionPlan(
            itemType = itemType,
            readPermissions = listOf(Manifest.permission.READ_CONTACTS),
            writePermissions = listOf(Manifest.permission.WRITE_CONTACTS),
        )

        ItemType.CALENDAR, ItemType.REMINDER -> ItemPermissionPlan(
            itemType = itemType,
            readPermissions = listOf(Manifest.permission.READ_CALENDAR),
            writePermissions = listOf(Manifest.permission.WRITE_CALENDAR),
        )

        ItemType.CALL_LOG -> ItemPermissionPlan(
            itemType = itemType,
            readPermissions = listOf(Manifest.permission.READ_CALL_LOG),
            writePermissions = listOf(Manifest.permission.WRITE_CALL_LOG),
            requiresSeparateConsent = true,
        )

        ItemType.PHOTO, ItemType.VIDEO -> ItemPermissionPlan(
            itemType = itemType,
            readPermissions = imageVideoReadPermissions(),
            writePermissions = emptyList(), // 写入走 MediaStore.insert + IS_PENDING，无需额外权限
        )

        ItemType.AUDIO -> ItemPermissionPlan(
            itemType = itemType,
            readPermissions = audioReadPermissions(),
            writePermissions = emptyList(),
        )

        // 文档一律由用户经 SAF 指定目录，不申请所有文件访问权限
        ItemType.DOCUMENT -> ItemPermissionPlan(
            itemType = itemType,
            readPermissions = emptyList(),
            writePermissions = emptyList(),
            requiresUserSelectedDirectory = true,
        )

        // 应用清单 / APK 依赖 <queries> 声明，不需要运行时权限
        ItemType.APP_LIST, ItemType.APK -> ItemPermissionPlan(
            itemType = itemType,
            readPermissions = emptyList(),
            writePermissions = emptyList(),
        )

        // V1.1：临时成为默认短信应用后才可读写（PoC P3）
        ItemType.SMS, ItemType.MMS -> ItemPermissionPlan(
            itemType = itemType,
            readPermissions = emptyList(),
            writePermissions = emptyList(),
            requiresSeparateConsent = true,
            requiresDefaultSmsApp = true,
        )

        ItemType.APP_DATA -> ItemPermissionPlan(
            itemType = itemType,
            readPermissions = emptyList(),
            writePermissions = emptyList(),
            requiresSeparateConsent = true, // 系统沙箱限制，实际走应用内官方迁移引导
        )

        else -> ItemPermissionPlan(
            itemType = itemType,
            readPermissions = emptyList(),
            writePermissions = emptyList(),
        )
    }

    /** 需要单独同意的数据项（L4）。 */
    val separateConsentItems: List<ItemType> =
        ItemType.entries.filter { it.sensitivity.requiresSeparateConsent }
}
