package com.phonetransfer.app.core.transport

import com.phonetransfer.app.core.protocol.ItemType
import java.io.File
import org.json.JSONObject

/**
 * 构造发送端要传的载荷。
 *
 * 目前只有「安装包（APK）」是**真实文件**（直接读本机已安装应用的 APK，可行性文档 FR-10），
 * 其余数据项的数据抽取需要各自的数据适配器，尚未实现——这里如实标记 implemented=false，
 * 而不是伪造内容。链路与协议本身已经是真实的。
 */
object PayloadBuilder {

    /** @param sourceApkPath 本机 APK 路径（applicationInfo.sourceDir） */
    fun build(selected: Collection<ItemType>, sourceApkPath: String?, apkLabel: String): List<PtPayload> {
        val list = ArrayList<PtPayload>()
        var id = 1L
        selected.sortedBy { it.code }.forEach { type ->
            val bytes = if (type == ItemType.APK) readApk(sourceApkPath) else null
            if (bytes != null) {
                list.add(PtPayload(id, type, apkLabel + ".apk", bytes))
            } else {
                val meta = JSONObject()
                    .put("itemType", type.code)
                    .put("typeName", type.name)
                    .put("implemented", false)
                    .put("reason", if (type == ItemType.APK) "apk-unavailable" else "adapter-not-implemented")
                    .toString()
                list.add(PtPayload(id, type, type.name.lowercase() + ".json", meta.toByteArray(Charsets.UTF_8)))
            }
            id += 1
        }
        return list
    }

    private fun readApk(path: String?): ByteArray? {
        if (path == null) return null
        val file = File(path)
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_APK_BYTES) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    private const val MAX_APK_BYTES = 200L * 1024 * 1024
}
