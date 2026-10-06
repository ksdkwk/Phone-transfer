package com.phonetransfer.app.core.protocol

/**
 * v1 协议参数与常量（规范 §16）。
 *
 * 约定：时间类常量单位均为毫秒；所有多字节整数一律大端（网络字节序）。
 */
object ProtocolConstants {

    /** 协议版本；帧头 version 与 T_PROTOCOL_VERSION 共用。 */
    const val PROTOCOL_VERSION: Int = 1

    /** 帧头 magic，即 "PTF" 加字节 0x01。 */
    const val MAGIC: Int = 0x50544601

    /** magic 的字节序列 0x50 0x54 0x46 0x01。 */
    val MAGIC_BYTES: ByteArray = byteArrayOf(0x50, 0x54, 0x46, 0x01)

    /** 固定帧头长度（字节）。 */
    const val FRAME_HEADER_SIZE: Int = 20

    /** payloadLen 上限，4 MiB；超出返回 FRAME_TOO_LARGE。 */
    const val MAX_FRAME_PAYLOAD: Int = 4 * 1024 * 1024

    /** CHUNK 数据块默认大小 1 MiB。 */
    const val CHUNK_SIZE: Int = 1024 * 1024

    /** CHUNK_SIZE 允许下限 256 KiB。 */
    const val MIN_CHUNK_SIZE: Int = 256 * 1024

    /** CHUNK_SIZE 允许上限 4 MiB。 */
    const val MAX_CHUNK_SIZE: Int = 4 * 1024 * 1024

    /** 信用窗口默认 8 MiB。 */
    const val WINDOW_SIZE: Int = 8 * 1024 * 1024

    /** WINDOW_SIZE 允许下限 1 MiB。 */
    const val MIN_WINDOW_SIZE: Int = 1024 * 1024

    /** WINDOW_SIZE 允许上限 32 MiB。 */
    const val MAX_WINDOW_SIZE: Int = 32 * 1024 * 1024

    /** 批量 ACK 时间间隔 50 ms。 */
    const val ACK_INTERVAL: Long = 50L

    /** 批量 ACK 块数间隔：每 8 块或每 50 ms 取先到者。 */
    const val ACK_INTERVAL_CHUNKS: Int = 8

    /** 等待 ACK 超时 5000 ms。 */
    const val ACK_TIMEOUT: Long = 5000L

    /** ACK_TIMEOUT 允许下限。 */
    const val MIN_ACK_TIMEOUT: Long = 1000L

    /** ACK_TIMEOUT 允许上限。 */
    const val MAX_ACK_TIMEOUT: Long = 15000L

    /** 单块重试上限 5 次，超限报 ACK_TIMEOUT 并暂停会话。 */
    const val MAX_RETRY: Int = 5

    /** MAX_RETRY 允许下限。 */
    const val MIN_RETRY: Int = 3

    /** MAX_RETRY 允许上限。 */
    const val MAX_RETRY_ALLOWED: Int = 10

    /** 握手总超时 10000 ms。 */
    const val HANDSHAKE_TIMEOUT: Long = 10000L

    /** SAS 人工比对有效期 120 s。 */
    const val SAS_TTL: Long = 120_000L

    /** 保活与 RTT 采样间隔 3000 ms。 */
    const val PING_INTERVAL: Long = 3000L

    /** 保活超时 10000 ms，超时判定 PEER_GONE。 */
    const val PING_TIMEOUT: Long = 10000L

    /** 会话无活动超时 30 min，超时销毁密钥。 */
    const val SESSION_TTL: Long = 30 * 60 * 1000L

    /** 续传位图持久化间隔 5 s。 */
    const val BITMAP_PERSIST_INTERVAL: Long = 5000L

    /** 续传位图持久化块数间隔 64 块。 */
    const val BITMAP_PERSIST_CHUNKS: Int = 64

    /** LAN 模式默认端口。 */
    const val DEFAULT_PORT: Int = 45717

    /** GCM Nonce 长度 12 字节 = be32(sessionId) + be64(msgSeq)。 */
    const val GCM_NONCE_SIZE: Int = 12

    /** GCM Tag 长度 16 字节，附于密文尾部。 */
    const val GCM_TAG_SIZE: Int = 16

    /** AES-256 密钥长度。 */
    const val AES_KEY_SIZE: Int = 32

    /** SHA-256 摘要长度。 */
    const val SHA256_SIZE: Int = 32

    /** 握手随机数 T_NONCE 长度。 */
    const val HANDSHAKE_NONCE_SIZE: Int = 16

    /** T_DEVICE_ID 长度，会话级随机值。 */
    const val DEVICE_ID_SIZE: Int = 16

    /** 压缩格式 P-256 公钥长度。 */
    const val ECDH_PUBKEY_SIZE: Int = 33

    /** 非压缩 P-256 公钥长度。 */
    const val ECDH_PUBKEY_UNCOMPRESSED_SIZE: Int = 65

    /** ECDH 共享密钥（x 坐标）长度。 */
    const val ECDH_SHARED_SECRET_SIZE: Int = 32

    /** CHUNK 明文固定头长度。 */
    const val CHUNK_FIXED_HEADER_SIZE: Int = 60

    /** 配对码位数。 */
    const val PAIR_CODE_LENGTH: Int = 6

    /** 配对码连续失败上限，达到后冷却。 */
    const val PAIR_MAX_ATTEMPTS: Int = 5

    /** 配对码失败冷却时间 60 s。 */
    const val PAIR_COOLDOWN: Long = 60_000L

    /** §7.5 T_KDF_INFO 默认派生上下文。 */
    const val KDF_INFO_SESSION: String = "pt/v1/session"

    /** k_c2s 的 HKDF-Expand info 前缀。 */
    const val KDF_INFO_C2S: String = "pt/v1/c2s"

    /** k_s2c 的 HKDF-Expand info 前缀。 */
    const val KDF_INFO_S2C: String = "pt/v1/s2c"

    /** SAS 的 HKDF-Expand info。 */
    const val KDF_INFO_SAS: String = "pt/v1/sas"

    /** Bonjour/mDNS 服务名。 */
    const val SERVICE_NAME: String = "_pt-v1._tcp"

    /** 厂商/私有扩展 tag 起始值。 */
    const val TAG_VENDOR_MIN: Int = 0x8000

    /** 厂商/私有扩展 tag 结束值。 */
    const val TAG_VENDOR_MAX: Int = 0xFFFF

    /** payloadLen 是否在允许范围内。 */
    fun isPayloadLenAllowed(payloadLen: Int): Boolean = payloadLen >= 0 && payloadLen <= MAX_FRAME_PAYLOAD

    /** CHUNK_SIZE 是否在允许范围内。 */
    fun isChunkSizeAllowed(chunkSize: Int): Boolean = chunkSize in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE

    /** WINDOW_SIZE 是否在允许范围内。 */
    fun isWindowSizeAllowed(windowSize: Int): Boolean = windowSize in MIN_WINDOW_SIZE..MAX_WINDOW_SIZE

    /** ACK_TIMEOUT 是否在允许范围内。 */
    fun isAckTimeoutAllowed(timeout: Long): Boolean = timeout in MIN_ACK_TIMEOUT..MAX_ACK_TIMEOUT

    /** 块级重试次数是否在允许范围内。 */
    fun isRetryAllowed(retry: Int): Boolean = retry in MIN_RETRY..MAX_RETRY_ALLOWED

    /** 是否为厂商/私有扩展 tag。 */
    fun isVendorTag(tag: Int): Boolean = tag in TAG_VENDOR_MIN..TAG_VENDOR_MAX
}
