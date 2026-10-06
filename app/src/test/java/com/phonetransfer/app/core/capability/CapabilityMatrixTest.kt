package com.phonetransfer.app.core.capability

import com.phonetransfer.app.core.protocol.ItemSupport
import com.phonetransfer.app.core.protocol.ItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 能力矩阵的回归测试：矩阵是「能力地图」与能力协商的数据源，
 * 一旦漏项或写错方向，界面会误导用户——所以这里的断言是产品级的，不是形式化的。
 */
class CapabilityMatrixTest {

    @Test
    fun baselineCoversEveryItemType() {
        val covered = CapabilityMatrix.baseline.map { it.itemType }.toSet()
        assertEquals(ItemType.entries.toSet(), covered)
    }

    @Test
    fun baselineHasNoDuplicates() {
        val types = CapabilityMatrix.baseline.map { it.itemType }
        assertEquals("基线矩阵不应有重复数据项", types.size, types.toSet().size)
    }

    @Test
    fun androidToAndroidSupportsTheMvpCoreItems() {
        val mvp = listOf(ItemType.CONTACT, ItemType.PHOTO, ItemType.VIDEO, ItemType.CALENDAR)
        mvp.forEach { type ->
            assertEquals(
                "$type 在 A→A 下应为完整支持",
                ItemSupport.SUPPORTED,
                CapabilityMatrix.supportOf(type, Scenario.A_TO_A),
            )
        }
    }

    @Test
    fun smsAndCallLogArePartialOnAndroidAndBlockedTowardsIos() {
        assertEquals(ItemSupport.PARTIAL, CapabilityMatrix.supportOf(ItemType.SMS, Scenario.A_TO_A))
        assertEquals(ItemSupport.PARTIAL, CapabilityMatrix.supportOf(ItemType.CALL_LOG, Scenario.A_TO_A))
        listOf(Scenario.A_TO_IOS, Scenario.IOS_TO_A, Scenario.IOS_TO_IOS).forEach { scenario ->
            assertEquals(
                "短信在 $scenario 下必须明确标注为不支持（可行性文档 §4.2）",
                ItemSupport.UNSUPPORTED,
                CapabilityMatrix.supportOf(ItemType.SMS, scenario),
            )
        }
    }

    @Test
    fun platformBlockedItemsStayUnsupportedEverywhere() {
        val blocked = listOf(
            ItemType.NOTE,
            ItemType.APP_DATA,
            ItemType.WIFI_CONFIG,
            ItemType.BROWSER_BOOKMARK,
        )
        blocked.forEach { type ->
            Scenario.entries.forEach { scenario ->
                assertEquals(
                    "$type 在 $scenario 下都应是不支持",
                    ItemSupport.UNSUPPORTED,
                    CapabilityMatrix.supportOf(type, scenario),
                )
            }
        }
    }

    @Test
    fun criticalItemsRequireSeparateConsent() {
        val critical = listOf(ItemType.SMS, ItemType.MMS, ItemType.CALL_LOG, ItemType.APP_DATA)
        critical.forEach { type ->
            assertTrue("$type 应被标记为 L4 极高敏感", type.sensitivity.requiresSeparateConsent)
        }
    }
}
