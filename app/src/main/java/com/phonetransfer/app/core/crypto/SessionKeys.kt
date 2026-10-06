package com.phonetransfer.app.core.crypto

import java.util.Arrays

/**
 * 会话密钥容器（规范 §10）。
 *
 * 密钥仅存内存、仅本会话使用；销毁时尽力覆写字节数组，重新使用需完整重新配对。
 */
class SessionKeys(
    /** 旧机到新机方向的 AES-256 密钥 k_c2s。 */
    val kC2S: ByteArray,
    /** 新机到旧机方向的 AES-256 密钥 k_s2c。 */
    val kS2C: ByteArray,
    /** 4 位数字 SAS，取值 0 - 9999。 */
    val sas: Int,
) : AutoCloseable {

    init {
        require(kC2S.size == KEY_SIZE) { "k_c2s 必须为 " + KEY_SIZE + " 字节, 实际 " + kC2S.size }
        require(kS2C.size == KEY_SIZE) { "k_s2c 必须为 " + KEY_SIZE + " 字节, 实际 " + kS2C.size }
        require(sas in 0 until SAS_MODULUS) { "SAS 越界: $sas" }
    }

    @Volatile
    private var destroyed = false

    /** 密钥是否已销毁。 */
    val isDestroyed: Boolean get() = destroyed

    /** SAS 的 4 位补零文本，供 UI 展示。 */
    val sasDigits: String get() = sas.toString().padStart(4, '0')

    override fun close() {
        destroy()
    }

    /** 覆写两个方向密钥，可重复调用。 */
    fun destroy() {
        if (destroyed) return
        Arrays.fill(kC2S, 0.toByte())
        Arrays.fill(kS2C, 0.toByte())
        destroyed = true
    }

    override fun toString(): String = "SessionKeys(sas=" + sasDigits + ", destroyed=" + destroyed + ")"

    companion object {
        /** AES-256 密钥长度。 */
        const val KEY_SIZE: Int = 32

        /** SAS 取值模数。 */
        const val SAS_MODULUS: Int = 10000
    }
}
