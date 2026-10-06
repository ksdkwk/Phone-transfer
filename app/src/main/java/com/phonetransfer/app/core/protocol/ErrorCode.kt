package com.phonetransfer.app.core.protocol

/** 错误码类别（规范 §15）。 */
enum class ErrorCategory {
    PROTOCOL,
    SECURITY,
    PAIRING,
    PERMISSION_STORAGE,
    TRANSFER,
    CHANNEL,
    USER
}

/** 协议错误码（规范 §15）。 */
enum class ErrorCode(val code: Int, val category: ErrorCategory, val text: String, val isFatal: Boolean) {

    OK(0x0000, ErrorCategory.PROTOCOL, "成功", false),
    PROTOCOL_VERSION_MISMATCH(0x1001, ErrorCategory.PROTOCOL, "协议版本不匹配，请升级 App", true),
    MISSING_FIELD(0x1002, ErrorCategory.PROTOCOL, "缺少必备字段", true),
    MALFORMED_TLV(0x1003, ErrorCategory.PROTOCOL, "TLV 或帧格式非法", true),
    FRAME_TOO_LARGE(0x1004, ErrorCategory.PROTOCOL, "帧 payload 超过 4 MiB 上限", false),
    DECRYPT_FAILED(0x1005, ErrorCategory.SECURITY, "解密失败，可能遭遇篡改", true),
    BAD_TRANSCRIPT(0x1006, ErrorCategory.SECURITY, "握手转录哈希不一致，疑似中间人", true),
    PAIR_CODE_MISMATCH(0x2001, ErrorCategory.PAIRING, "配对码不匹配", true),
    SAS_REJECTED(0x2002, ErrorCategory.PAIRING, "用户拒绝 SAS", true),
    SAS_TIMEOUT(0x2003, ErrorCategory.PAIRING, "SAS 确认超时", true),
    SESSION_EXPIRED(0x2004, ErrorCategory.PAIRING, "会话已过期，需重新配对", true),
    PERMISSION_DENIED(0x3001, ErrorCategory.PERMISSION_STORAGE, "权限被拒绝，可跳过该项", false),
    PERMISSION_PERMANENTLY_DENIED(0x3002, ErrorCategory.PERMISSION_STORAGE, "权限被永久拒绝，需去系统设置", false),
    DEFAULT_SMS_APP_REQUIRED(0x3003, ErrorCategory.PERMISSION_STORAGE, "需要设为默认短信应用", false),
    NOT_DEFAULT_SMS_APP(0x3004, ErrorCategory.PERMISSION_STORAGE, "当前不是默认短信应用", false),
    STORAGE_FULL(0x3005, ErrorCategory.PERMISSION_STORAGE, "目标存储空间不足", false),
    SOURCE_NOT_FOUND(0x3006, ErrorCategory.PERMISSION_STORAGE, "源文件已删除", false),
    WRITE_FAILED(0x3007, ErrorCategory.PERMISSION_STORAGE, "写入失败", false),
    ITEM_UNSUPPORTED(0x3008, ErrorCategory.PERMISSION_STORAGE, "数据项不支持迁移", false),
    CHUNK_SHA_MISMATCH(0x4001, ErrorCategory.TRANSFER, "数据块摘要不一致", false),
    ITEM_SHA_MISMATCH(0x4002, ErrorCategory.TRANSFER, "条目整体摘要不一致", false),
    ACK_TIMEOUT(0x4003, ErrorCategory.TRANSFER, "确认超时，可续传", false),
    WINDOW_EXHAUSTED(0x4004, ErrorCategory.TRANSFER, "信用窗口耗尽（正常流控）", false),
    CHANNEL_LOST(0x5001, ErrorCategory.CHANNEL, "通道中断，可自动重连", false),
    PEER_GONE(0x5002, ErrorCategory.CHANNEL, "对端无响应", true),
    THERMAL_THROTTLE(0x5003, ErrorCategory.CHANNEL, "热限速", false),
    USER_CANCELLED(0x6001, ErrorCategory.USER, "用户取消", false),
    INTERNAL_ERROR(0x6002, ErrorCategory.USER, "内部错误", true);

    /** 是否为成功码。 */
    val isOk: Boolean get() = this == OK

    /** 会话是否可继续（非致命且非成功）。 */
    val isRecoverable: Boolean get() = !isFatal && this != OK

    /** 是否建议重试同一操作。 */
    val isRetryable: Boolean
        get() = when (this) {
            FRAME_TOO_LARGE,
            CHUNK_SHA_MISMATCH,
            ITEM_SHA_MISMATCH,
            ACK_TIMEOUT,
            WINDOW_EXHAUSTED,
            CHANNEL_LOST,
            THERMAL_THROTTLE,
            WRITE_FAILED -> true
            else -> false
        }

    companion object {
        /** 按数值查错误码，未知返回 null。 */
        fun fromCode(code: Int): ErrorCode? = values().firstOrNull { it.code == code }

        /** 按数值查错误码，未知抛 MALFORMED_TLV。 */
        fun requireFromCode(code: Int): ErrorCode =
            fromCode(code) ?: throw ProtocolException(ErrorCode.MALFORMED_TLV, "未知错误码: " + code)

        /** 按数值查错误码，未知返回兜底值。 */
        fun fromCodeOrDefault(code: Int, fallback: ErrorCode = INTERNAL_ERROR): ErrorCode = fromCode(code) ?: fallback
    }
}
