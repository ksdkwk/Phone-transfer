package com.phonetransfer.app.softap

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.phonetransfer.app.core.backup.BackupNames
import com.phonetransfer.app.core.protocol.ItemType
import com.phonetransfer.app.core.transport.PtManifestEntry
import com.phonetransfer.app.core.transport.PtPayload
import com.phonetransfer.app.core.transport.PtReceiveTarget
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * SAF-only 备份文件装配：旧机一侧把用户选中的文档变成 [PtPayload]，
 * 新机一侧把清单条目落成备份目录里的真实文件（全部经 SAF，不申请整盘权限）。
 */
class BackupDocuments(private val context: Context, private val cancelled: () -> Boolean) {
    private val resolver = context.contentResolver

    /** 逐个读取所选文档：一边算 SHA-256（清单需要），一边拿到精确大小。 */
    fun prepare(uris: List<Uri>, progress: (Int) -> Unit): List<PtPayload> {
        require(uris.isNotEmpty() && uris.size <= 512)
        return uris.distinct().mapIndexed { index, uri ->
            check(!cancelled()) { "已取消" }
            var name = "file-" + (index + 1)
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
            val hash = MessageDigest.getInstance("SHA-256")
            var size = 0L
            open(uri).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    check(!cancelled()) { "已取消" }
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    hash.update(buffer, 0, n)
                    size = Math.addExact(size, n.toLong())
                }
            }
            progress(index + 1)
            val mime = resolver.getType(uri).orEmpty()
            val type = when {
                mime.startsWith("image/") -> ItemType.PHOTO
                mime.startsWith("video/") -> ItemType.VIDEO
                mime.startsWith("audio/") -> ItemType.AUDIO
                name.endsWith(".apk", true) -> ItemType.APK
                else -> ItemType.DOCUMENT
            }
            PtPayload(index + 1L, type, BackupNames.safe(name), size, hash.digest(), { open(uri) })
        }
    }

    private fun open(uri: Uri) = resolver.openInputStream(uri) ?: error("无法读取所选文件")

    /** 在备份根目录下建本次会话子目录，名字带时间戳避免冲突。 */
    fun createSessionDirectory(tree: Uri, name: String): Uri {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: error("备份目录不可写")
    }

    private fun childExists(tree: Uri, parent: Uri, name: String): Boolean {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(0) == name) return true
        }
        return false
    }

    /**
     * 接收端落盘目标：立即在 SAF 里创建目标文档并直接流式写入，
     * commit 时回读校验长度 + SHA-256；失败/取消即删除，不留半截文件。
     */
    fun target(tree: Uri, parent: Uri, entry: PtManifestEntry): PtReceiveTarget {
        check(!cancelled()) { "已取消" }
        val name = BackupNames.unique(entry.name) { childExists(tree, parent, it) }
        val dest = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)
            ?: error("无法创建备份文件")
        val stream = BufferedOutputStream(resolver.openOutputStream(dest, "w") ?: error("无法写入备份目录"))
        return object : PtReceiveTarget {
            override val output: OutputStream = stream
            private var committed = false
            override fun commit(): String {
                stream.flush()
                stream.close()
                check(!cancelled()) { "已取消" }
                try {
                    // Read back what the provider actually stored: only length + digest
                    // both matching the manifest counts as a verified backup.
                    val verify = MessageDigest.getInstance("SHA-256")
                    var stored = 0L
                    resolver.openInputStream(dest)?.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check(!cancelled()) { "已取消" }
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (n == 0) continue
                            verify.update(buffer, 0, n)
                            stored += n
                        }
                    } ?: error("备份文件读取失败")
                    check(stored == entry.size) { "备份长度不符: " + stored + " != " + entry.size }
                    check(verify.digest().contentEquals(entry.sha256)) { "备份回读校验失败" }
                    committed = true
                    return dest.toString()
                } catch (e: Exception) {
                    abort()
                    throw e
                }
            }
            override fun abort() {
                runCatching { stream.close() }
                if (!committed) runCatching { DocumentsContract.deleteDocument(resolver, dest) }
            }
        }
    }
}
