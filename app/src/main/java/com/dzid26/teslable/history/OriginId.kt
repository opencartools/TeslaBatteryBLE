// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * The random id of this install, written into every row it acquires (ADR-0014). It sits in the
 * no-backup directory, so a restored backup on another phone mints its own.
 */
internal object OriginId {
    private const val FILE_NAME = "history-origin-id"

    fun load(context: Context): String {
        val file = File(context.noBackupFilesDir, FILE_NAME)
        val saved = runCatching { file.readText().trim() }.getOrDefault("")
        if (saved.isNotEmpty()) return saved
        return UUID.randomUUID().toString().also { runCatching { file.writeText(it) } }
    }
}
