package com.phonetransfer.app.core.protocol

/**
 * 承载 [ErrorCode] 的协议异常。
 *
 * 上层捕获后可直接以 T_ERROR_CODE 回 ERROR 消息；[errorCode] 为致命码时应终止会话。
 */
class ProtocolException(
    val errorCode: ErrorCode,
    detail: String? = null,
    cause: Throwable? = null,
) : Exception(detail ?: errorCode.text, cause) {

    /** 规范错误码数值，便于直接写入 T_ERROR_CODE。 */
    val code: Int get() = errorCode.code

    override fun toString(): String =
        "ProtocolException(code=0x" + code.toString(16) + ", " + errorCode.name + ": " + message + ")"

    companion object {
        /** 缺少必备字段。 */
        fun missingField(field: String): ProtocolException =
            ProtocolException(ErrorCode.MISSING_FIELD, "缺少必备字段: " + field)

        /** 编码/解码格式非法。 */
        fun malformed(detail: String): ProtocolException =
            ProtocolException(ErrorCode.MALFORMED_TLV, detail)

        /** payloadLen 超限。 */
        fun frameTooLarge(payloadLen: Int): ProtocolException =
            ProtocolException(ErrorCode.FRAME_TOO_LARGE, "payloadLen=" + payloadLen + " 超过上限 " + ProtocolConstants.MAX_FRAME_PAYLOAD)

        /** 协议版本不匹配。 */
        fun versionMismatch(local: Int, peer: Int): ProtocolException =
            ProtocolException(ErrorCode.PROTOCOL_VERSION_MISMATCH, "协议版本不匹配: 本端 " + local + ", 对端 " + peer)

        /** GCM 解密或完整性校验失败。 */
        fun decryptFailed(cause: Throwable? = null): ProtocolException =
            ProtocolException(ErrorCode.DECRYPT_FAILED, "GCM 解密或完整性校验失败", cause)

        /** 握手转录哈希不一致。 */
        fun badTranscript(): ProtocolException =
            ProtocolException(ErrorCode.BAD_TRANSCRIPT, "握手转录哈希不一致")
    }
}
