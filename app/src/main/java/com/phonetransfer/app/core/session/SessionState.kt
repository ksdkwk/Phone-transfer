package com.phonetransfer.app.core.session

/** 会话状态（规范 §14）。 */
enum class SessionState {

    /** 启动态。 */
    IDLE,

    /** 开始发现。 */
    DISCOVERING,

    /** 已发送/收到 HELLO 与 HELLO_ACK。 */
    HELLO,

    /** 配对进行中（PAIR_REQUEST / PAIR_ACCEPT）。 */
    PAIRING,

    /** 双方公钥就绪。 */
    KEY_EXCHANGE,

    /** SAS_NOTIFY 已互发，等待人工比对与确认。 */
    SAS_PENDING,

    /** 双方 SAS_CONFIRM 完成。 */
    READY,

    /** 能力协商与清单交互。 */
    NEGOTIATING,

    /** 数据传输中。 */
    TRANSFERRING,

    /** 暂停（用户/温升/低电量/网络/退后台）。 */
    PAUSED,

    /** 通道中断后重连。 */
    RESUMING,

    /** 校验与比对摘要。 */
    VERIFYING,

    /** 收尾与报告。 */
    COMPLETING,

    /** 会话关闭。 */
    CLOSED,

    /** 不可恢复错误。 */
    FAILED;

    /** 是否为本会话内的终态：CLOSED 与 FAILED。 */
    val isTerminal: Boolean get() = this == CLOSED || this == FAILED

    /** 是否为失败态。 */
    val isFailure: Boolean get() = this == FAILED

    /** 是否处于数据传输相关阶段。 */
    val isTransferPhase: Boolean get() = this == TRANSFERRING || this == PAUSED || this == RESUMING
}
