package com.phonetransfer.app.core.backup

/** Remote names are labels, never relative paths. No overwrite of existing backups. */
object BackupNames {
    fun safe(name: String): String {
        val clean = name.map { c ->
            if (c.code < 32 || c in "/\\:*?\"<>|") '_' else c
        }.joinToString("").trim().trim('.')
        return clean.take(160).ifBlank { "unnamed" }
    }

    fun unique(name: String, exists: (String) -> Boolean): String {
        val base = safe(name)
        if (!exists(base)) return base
        val dot = base.lastIndexOf('.').takeIf { it > 0 } ?: base.length
        val stem = base.substring(0, dot)
        val suffix = base.substring(dot)
        for (n in 1..10000) {
            val candidate = stem + " (" + n + ")" + suffix
            if (!exists(candidate)) return candidate
        }
        error("同名文件过多")
    }
}
