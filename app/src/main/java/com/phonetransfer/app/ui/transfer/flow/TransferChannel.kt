package com.phonetransfer.app.ui.transfer.flow

import androidx.annotation.StringRes
import com.phonetransfer.app.R

/**
 * 换机技术（传输通道），取值口径来自《可行性设计文档》§4.3「传输通道可行性对比」。
 *
 * 取舍：5 项全部如实展示，但不把不可行的项伪装成可选：
 *  · USB 数据线：手机之间直连当前不可行（AOA/ADB 受限，iOS 端需 MFi），只在 V2 的 PC 中转场景预留；
 *  · 蓝牙：只用于设备发现与握手，不承载大文件（0.5–3 MB/s）。
 * 这两项置灰并写明原因，避免用户选完才发现不可用（诚实降级，可行性文档 §6.1）。
 */
enum class TransferChannel(
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    @StringRes val detailRes: Int,
    val selectable: Boolean,
) {
    /** 旧机开热点，新机加入；通用兜底。 */
    SOFT_AP(R.string.channel_softap, R.string.channel_softap_desc, R.string.channel_softap_detail, true),

    /** 两台手机连同一个路由器，mDNS 互相发现；涉及 iOS 时的主力通道。 */
    LAN_MDNS(R.string.channel_lan_mdns, R.string.channel_lan_mdns_desc, R.string.channel_lan_mdns_detail, true),

    /** Android↔Android 首选：两机直连，不经过路由器。 */
    WIFI_DIRECT(R.string.channel_wifi_direct, R.string.channel_wifi_direct_desc, R.string.channel_wifi_direct_detail, true),

    /** 本期不可选：手机直连不可行。 */
    USB(R.string.channel_usb, R.string.channel_usb_desc, R.string.channel_usb_detail, false),

    /** 本期不可选：仅用于发现与握手。 */
    BLUETOOTH(R.string.channel_bluetooth, R.string.channel_bluetooth_desc, R.string.channel_bluetooth_detail, false),
}
