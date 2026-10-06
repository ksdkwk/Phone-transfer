package com.phonetransfer.app.core.protocol

/** 消息类型枚举（规范 §8）。 */
enum class MessageType(val code: Int) {
    HELLO(0x01),
    HELLO_ACK(0x02),
    PAIR_REQUEST(0x03),
    PAIR_ACCEPT(0x04),
    KEY_EXCHANGE(0x05),
    SAS_NOTIFY(0x06),
    SAS_CONFIRM(0x07),
    SESSION_READY(0x08),
    CAPABILITY_QUERY(0x09),
    CAPABILITY_RESPONSE(0x0A),
    MANIFEST_OFFER(0x0B),
    ITEM_SELECT(0x0C),
    TRANSFER_START(0x0D),
    ITEM_BEGIN(0x10),
    CHUNK(0x11),
    CHUNK_ACK(0x12),
    ITEM_END(0x13),
    TRANSFER_COMPLETE(0x14),
    VERIFY_RESULT(0x15),
    CONTROL(0x20),
    PING(0x21),
    PONG(0x22),
    ERROR(0x30),
    SESSION_CLOSE(0x31);

    /** 是否属于握手段（HELLO 至 KEY_EXCHANGE 明文传输）。 */
    val isHandshake: Boolean
        get() = this == HELLO || this == HELLO_ACK || this == PAIR_REQUEST ||
            this == PAIR_ACCEPT || this == KEY_EXCHANGE

    /** 该消息的 payload 是否必须加密（§8 加密列）。 */
    val requiresEncryption: Boolean get() = !isHandshake

    companion object {
        /** 按数值查消息类型，未知返回 null（不允许崩溃）。 */
        fun fromCode(code: Int): MessageType? = values().firstOrNull { it.code == code }

        /** 按数值查消息类型，未知抛 MALFORMED_TLV。 */
        fun requireFromCode(code: Int): MessageType =
            fromCode(code) ?: throw ProtocolException(ErrorCode.MALFORMED_TLV, "未知消息类型: $code")
    }
}
