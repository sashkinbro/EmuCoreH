
package com.sbro.emucoreh.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Stage 15: SAF file access per docs §41, §24-26
 * Never converts SAF URI to fake filesystem path.
 * Uses ContentResolver for disc/BIOS/memcard access.
 */
object SafFileHelper {

    fun getDisplayName(context: Context, uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return null
    }

    fun openInputStream(context: Context, uri: Uri) = context.contentResolver.openInputStream(uri)

    fun isValidBiosUri(context: Context, uri: Uri): Boolean {
        // Check size is 512KB per docs §10, not loading full file yet
        val cursor = context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val size = it.getLong(0)
                return size == 512L * 1024L
            }
        }
        // Fallback: try to get size via open
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length == 512L*1024L } ?: false
        } catch (_: Throwable) { false }
    }

    fun isValidDiscUri(context: Context, uri: Uri): Boolean {
        val name = getDisplayName(context, uri) ?: return false
        return com.sbro.emucoreh.core.GameFormats.isSupportedName(name)
    }
}
