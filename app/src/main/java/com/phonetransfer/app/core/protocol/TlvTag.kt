package com.phonetransfer.app.core.protocol

/**
 * TLV tag 常量（规范 §7.3 - §7.10）。
 *
 * 未知 tag 必须按 len 前移跳过，不得报错；厂商扩展 tag 位于 0x8000 - 0xFFFF。
 */
object TlvTag {

    // ---- 通用字段 0x0001 - 0x000E ----

    /** u32 会话标识。 */
    const val T_SESSION_ID: Int = 0x0001

    /** bytes(16) 会话级随机设备标识，禁止使用 IMEI/序列号。 */
    const val T_DEVICE_ID: Int = 0x0002

    /** string 设备品牌。 */
    const val T_DEVICE_BRAND: Int = 0x0003

    /** string 设备型号。 */
    const val T_DEVICE_MODEL: Int = 0x0004

    /** enum 操作系统：1=Android 2=iOS 3=HarmonyOS。 */
    const val T_OS_NAME: Int = 0x0005

    /** string 系统版本。 */
    const val T_OS_VERSION: Int = 0x0006

    /** string 应用语义化版本。 */
    const val T_APP_VERSION: Int = 0x0007

    /** u16 协议版本。 */
    const val T_PROTOCOL_VERSION: Int = 0x0008

    /** u64 Unix 毫秒时间戳。 */
    const val T_TIMESTAMP: Int = 0x0009

    /** bytes(16) 握手随机数。 */
    const val T_NONCE: Int = 0x000A

    /** bytes(32) 握手转录哈希。 */
    const val T_TRANSCRIPT_HASH: Int = 0x000B

    /** u32 错误码，见 §15。 */
    const val T_ERROR_CODE: Int = 0x000C

    /** string 面向日志的错误描述。 */
    const val T_ERROR_MESSAGE: Int = 0x000D

    /** enum 关闭或中断原因。 */
    const val T_REASON: Int = 0x000E

    // ---- 发现与配对 0x0100 - 0x01FF ----

    /** enum 发现方式：1=蓝牙 2=Bonjour/mDNS 3=Wi-Fi Aware 4=手动。 */
    const val T_DISCOVERY_METHOD: Int = 0x0101

    /** enum 配对方式：1=二维码 2=6 位配对码 3=近距离自动。 */
    const val T_PAIR_METHOD: Int = 0x0102

    /** bytes(32) SHA256(code + deviceIdC + deviceIdS)。 */
    const val T_PAIR_CODE_HASH: Int = 0x0103

    /** bytes 二维码内容。 */
    const val T_QR_PAYLOAD: Int = 0x0104

    /** string 端点 IP 地址。 */
    const val T_ENDPOINT_ADDR: Int = 0x0105

    /** u16 端点端口，默认 45717。 */
    const val T_ENDPOINT_PORT: Int = 0x0106

    /** enum 通道类型：1=Wi-Fi Direct 2=Soft AP 3=LAN 4=Cloud。 */
    const val T_CHANNEL_TYPE: Int = 0x0107

    /** enum 角色：1=SENDER(旧机) 2=RECEIVER(新机)。 */
    const val T_ROLE: Int = 0x0108

    /** u64 通道能力位图。 */
    const val T_CAPABILITY_BITMASK: Int = 0x0109

    /** string Bonjour 服务名。 */
    const val T_SERVICE_NAME: Int = 0x010A

    // ---- 密钥协商与认证 0x0200 - 0x02FF ----

    /** enum 加密套件：1=ECDH-P256+HKDF-SHA256+AES-256-GCM。 */
    const val T_CIPHER_SUITE: Int = 0x0201

    /** bytes(33) 压缩格式 P-256 公钥。 */
    const val T_ECDH_PUBKEY: Int = 0x0202

    /** u16 密钥标识，支持重协商。 */
    const val T_KEY_ID: Int = 0x0203

    /** u16 4 位数字 SAS，取值 0-9999。 */
    const val T_SAS: Int = 0x0204

    /** enum SAS 格式：1=NUMERIC4 2=EMOJI7（预留）。 */
    const val T_SAS_FORMAT: Int = 0x0205

    /** bool 用户确认 SAS 一致。 */
    const val T_SAS_CONFIRMED: Int = 0x0206

    /** bytes(32) SHA256(pubkey)。 */
    const val T_PUBKEY_FINGERPRINT: Int = 0x0207

    /** string 派生上下文，默认 pt/v1/session。 */
    const val T_KDF_INFO: Int = 0x0208

    // ---- 能力协商与清单 0x0300 - 0x030F ----

    /** enum 数据项类型，见 §7.7。 */
    const val T_ITEM_TYPE: Int = 0x0301

    /** enum 支持度：0=不支持 1=支持 2=部分支持 3=需额外授权。 */
    const val T_ITEM_SUPPORT: Int = 0x0302

    /** u32 清单版本，重选后递增。 */
    const val T_MANIFEST_ID: Int = 0x0303

    /** u32 该类型条目数。 */
    const val T_ITEM_COUNT: Int = 0x0304

    /** u64 该类型总字节数。 */
    const val T_TOTAL_BYTES: Int = 0x0305

    /** bytes 按 enum 位的已选类型位图。 */
    const val T_SELECTED_ITEM_TYPES: Int = 0x0306

    /** enum 冲突策略：1=重命名 2=跳过 3=覆盖 4=合并。 */
    const val T_CONFLICT_POLICY: Int = 0x0307

    /** bool 是否保留 EXIF 位置信息。 */
    const val T_INCLUDE_EXIF_LOCATION: Int = 0x0308

    /** bytes L4 数据项单独同意结果位图。 */
    const val T_SENSITIVE_CONSENT: Int = 0x0309

    /** u32 块大小，默认 1048576。 */
    const val T_CHUNK_SIZE: Int = 0x030A

    /** u32 信用窗口字节数，默认 8 MiB。 */
    const val T_WINDOW_SIZE: Int = 0x030B

    /** enum 压缩：0=无 1=zstd。 */
    const val T_COMPRESSION: Int = 0x030C

    // ---- 清单条目 ItemEntry 0x0310 - 0x0318 ----

    /** u64 会话内唯一条目 ID。 */
    const val T_ITEM_ID: Int = 0x0310

    /** string 显示名。 */
    const val T_ITEM_NAME: Int = 0x0311

    /** string 聚合类型可为 application/x-pt-aggregate。 */
    const val T_MIME: Int = 0x0312

    /** u64 字节数。 */
    const val T_ITEM_SIZE: Int = 0x0313

    /** bytes(32) 整体摘要，最终校验用。 */
    const val T_ITEM_SHA256: Int = 0x0314

    /** bytes JSON 元数据。 */
    const val T_ITEM_META: Int = 0x0315

    /** enum 敏感度：1=L1 2=L2 3=L3 4=L4。 */
    const val T_SENSITIVITY: Int = 0x0316

    /** string 相对路径。 */
    const val T_REL_PATH: Int = 0x0317

    /** enum 校验模式：0=整体 1=分块。 */
    const val T_CHECKSUM_MODE: Int = 0x0318

    // ---- 数据传输 0x0400 - 0x04FF ----

    /** 标记条目开始，携带 T_ITEM_ID / T_CHUNK_SIZE / T_BLOCK_COUNT。 */
    const val T_ITEM_BEGIN: Int = 0x0401

    /** 标记条目结束，携带 T_ITEM_ID / T_ITEM_SHA256。 */
    const val T_ITEM_END: Int = 0x0402

    /** u32 块序号，从 0 开始。 */
    const val T_CHUNK_SEQ: Int = 0x0403

    /** u64 块在原条目中的偏移。 */
    const val T_CHUNK_OFFSET: Int = 0x0404

    /** bytes(32) 明文块摘要。 */
    const val T_CHUNK_SHA256: Int = 0x0405

    /** u32 累积确认：已连续收到的最大 seq。 */
    const val T_CHUNK_ACK_BASE: Int = 0x0406

    /** u64 SACK 位图：bit i 表示 base+1+i 已收到。 */
    const val T_CHUNK_ACK_BITMAP: Int = 0x0407

    /** bytes 续传用已收块位图。 */
    const val T_RESUME_BITMAP: Int = 0x0408

    /** u32 条目总块数。 */
    const val T_BLOCK_COUNT: Int = 0x0409

    /** u32 接收端新增信用字节。 */
    const val T_CREDIT: Int = 0x040A

    /** u32 限速值 字节/秒，0 表示不限。 */
    const val T_RATE_LIMIT: Int = 0x040B

    /** enum 写入结果：1=成功 2=跳过 3=失败。 */
    const val T_WRITE_RESULT: Int = 0x040C

    /** u32 写入失败原因，取自 §15。 */
    const val T_FAIL_REASON: Int = 0x040D

    // ---- 控制类 0x0500 - 0x05FF ----

    /** enum 控制命令：1=暂停 2=恢复 3=取消 4=Ping 5=Pong 6=限速。 */
    const val T_CTRL_CMD: Int = 0x0501

    /** enum 暂停原因：1=用户 2=温升 3=低电量 4=网络 5=退后台。 */
    const val T_PAUSE_REASON: Int = 0x0502

    /** bytes(32) 恢复令牌，仅本地持久化。 */
    const val T_RESUME_TOKEN: Int = 0x0503

    /** u8 电量百分比。 */
    const val T_BATTERY_LEVEL: Int = 0x0504

    /** enum 热状态：0=正常 1=轻 2=中 3=重。 */
    const val T_THERMAL_STATE: Int = 0x0505

    // ---- 结束、校验与报告 0x0600 - 0x06FF ----

    /** bool 会话结束。 */
    const val T_SESSION_DONE: Int = 0x0601

    /** enum 校验结果：0=全部一致 1=摘要不一致 2=部分完成。 */
    const val T_VERIFY_RESULT: Int = 0x0602

    /** bytes JSON 迁移报告。 */
    const val T_REPORT: Int = 0x0603

    /** bytes JSON 数组，含 itemId 与错误码。 */
    const val T_FAILED_ITEMS: Int = 0x0604

    /** bool 接收端已清理临时文件与密钥。 */
    const val T_CLEANUP_CONFIRM: Int = 0x0605

    // ---- tag 区间边界 ----

    const val TAG_COMMON_START: Int = 0x0001
    const val TAG_COMMON_END: Int = 0x00FF
    const val TAG_DISCOVERY_START: Int = 0x0100
    const val TAG_DISCOVERY_END: Int = 0x01FF
    const val TAG_KEY_START: Int = 0x0200
    const val TAG_KEY_END: Int = 0x02FF
    const val TAG_MANIFEST_START: Int = 0x0300
    const val TAG_MANIFEST_END: Int = 0x03FF
    const val TAG_TRANSFER_START: Int = 0x0400
    const val TAG_TRANSFER_END: Int = 0x04FF
    const val TAG_CONTROL_START: Int = 0x0500
    const val TAG_CONTROL_END: Int = 0x05FF
    const val TAG_REPORT_START: Int = 0x0600
    const val TAG_REPORT_END: Int = 0x06FF
    const val TAG_VENDOR_START: Int = 0x8000
    const val TAG_VENDOR_END: Int = 0xFFFF

    /** 是否为规范已定义的 tag。 */
    fun isKnown(tag: Int): Boolean =
        tag in 0x0001..0x000E ||
            tag in 0x0101..0x010A ||
            tag in 0x0201..0x0208 ||
            tag in 0x0301..0x030C ||
            tag in 0x0310..0x0318 ||
            tag in 0x0401..0x040D ||
            tag in 0x0501..0x0505 ||
            tag in 0x0601..0x0605

    /** 是否为厂商/私有扩展 tag（必须可安全跳过）。 */
    fun isVendor(tag: Int): Boolean = tag in TAG_VENDOR_START..TAG_VENDOR_END

    /** 十六进制展示，便于日志。 */
    fun nameOf(tag: Int): String = "0x" + tag.toString(16).uppercase().padStart(4, '0')
}
