package com.blushdesk.app.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Copies a generated file into the public Downloads folder through MediaStore. On API 29+ an app
 * may add files to Downloads without any storage permission, and the user can then find the
 * receipt in the Files app next to everything else they have downloaded.
 */
class DownloadsSaver(private val context: Context) {

    /**
     * Saves [source] as Downloads/[FOLDER]/[displayName] and returns that human-readable path.
     * Saving the same name again replaces the earlier copy (a regenerated receipt must not leave
     * "Receipt (1).pdf", "Receipt (2).pdf" behind).
     */
    suspend fun save(source: File, displayName: String, mimeType: String): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/"

        // Rows this app created earlier are visible to it; rows other apps created are not.
        val existing = resolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(displayName, relativePath),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        }

        val uri = existing ?: resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            },
        ) ?: throw IOException("Could not create a file in Downloads")

        try {
            // "wt" truncates, so replacing a longer old file with a shorter one leaves no tail.
            resolver.openOutputStream(uri, "wt")?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("Could not write to Downloads")
        } catch (e: Exception) {
            if (existing == null) resolver.delete(uri, null, null)
            throw e
        }

        if (existing == null) {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
        }
        "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$displayName"
    }

    private companion object {
        const val FOLDER = APP_NAME
    }
}
