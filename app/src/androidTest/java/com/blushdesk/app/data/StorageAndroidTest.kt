package com.blushdesk.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blushdesk.app.utils.AppFiles
import com.blushdesk.app.utils.DownloadsSaver
import com.blushdesk.app.utils.PhotoStorage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class StorageAndroidTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val photos = PhotoStorage(context)
    private val created = mutableListOf<File>()

    @After
    fun cleanUp() {
        created.forEach { it.delete() }
        File(context.filesDir, "photos").listFiles()?.forEach { it.delete() }
        removeDownloads("androidtest-%")
    }

    private fun jpeg(name: String, width: Int, height: Int, orientation: Int? = null): File {
        val file = File(context.cacheDir, name).also { created += it }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(233, 30, 99)) }
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        if (orientation != null) {
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        return file
    }

    /** Size of the image behind a stored `file://` URI. */
    private fun bounds(imageUri: String) = BitmapFactory.Options().also {
        it.inJustDecodeBounds = true
        BitmapFactory.decodeFile(AppFiles.localFile(imageUri)!!.path, it)
    }

    // ---- PhotoStorage ----------------------------------------------------------------------

    @Test
    fun import_downsizes_a_large_photo_to_1024_on_the_long_edge() = runBlocking {
        val path = photos.importPhoto(Uri.fromFile(jpeg("big.jpg", 4000, 3000)))

        val b = bounds(path)
        assertEquals(1024, maxOf(b.outWidth, b.outHeight))
        assertEquals(768, minOf(b.outWidth, b.outHeight)) // aspect ratio 4:3 preserved
        assertTrue(path, path.startsWith("file://"))
        assertTrue(AppFiles.localFile(path)!!.path.startsWith(File(context.filesDir, "photos").absolutePath))
    }

    @Test
    fun import_does_not_enlarge_a_small_photo() = runBlocking {
        val path = photos.importPhoto(Uri.fromFile(jpeg("small.jpg", 300, 200)))
        val b = bounds(path)
        assertEquals(300, b.outWidth)
        assertEquals(200, b.outHeight)
    }

    @Test
    fun import_applies_the_exif_rotation() = runBlocking {
        // A 3000x2000 landscape file flagged "rotate 90" is really a portrait picture.
        val path = photos.importPhoto(Uri.fromFile(jpeg("rotated.jpg", 3000, 2000, ExifInterface.ORIENTATION_ROTATE_90)))
        val b = bounds(path)
        assertEquals(1024, b.outHeight)
        assertTrue("width should be about 683 but was ${b.outWidth}", b.outWidth in 682..684)
    }

    @Test
    fun import_rejects_a_file_that_is_not_an_image() {
        val text = File(context.cacheDir, "notes.txt").also { created += it; it.writeText("not a picture") }
        try {
            runBlocking { photos.importPhoto(Uri.fromFile(text)) }
            fail("expected an IOException")
        } catch (_: IOException) {
            // expected
        }
        assertEquals(0, File(context.filesDir, "photos").listFiles()?.size ?: 0)
    }

    @Test
    fun import_clears_the_camera_cache() = runBlocking {
        val camera = AppFiles.cacheDir(context, AppFiles.CAMERA_DIR)
        val leftover = File(camera, "capture_1.jpg").also { it.writeText("x") }
        photos.importPhoto(Uri.fromFile(jpeg("c.jpg", 400, 300)))
        assertFalse(leftover.exists())
    }

    @Test
    fun delete_removes_our_photos_but_never_files_outside_the_photos_folder() = runBlocking {
        val ours = photos.importPhoto(Uri.fromFile(jpeg("d.jpg", 400, 300)))
        val foreign = File(context.cacheDir, "precious.txt").also { created += it; it.writeText("keep me") }

        photos.delete(ours)
        photos.delete(foreign.absolutePath)
        photos.delete("../../etc/hosts")
        photos.delete(null)
        photos.delete("")

        assertFalse(AppFiles.localFile(ours)!!.exists())
        assertTrue(foreign.exists())
    }

    // ---- DownloadsSaver ----------------------------------------------------------------------

    private fun downloadsRows(namePattern: String): List<Pair<Long, String>> {
        val rows = mutableListOf<Pair<Long, String>>()
        context.contentResolver.query(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(namePattern, "${Environment.DIRECTORY_DOWNLOADS}/FergBentables/"),
            null,
        )?.use { while (it.moveToNext()) rows += it.getLong(0) to it.getString(1) }
        return rows
    }

    private fun removeDownloads(namePattern: String) {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        downloadsRows(namePattern).forEach { (id, _) ->
            context.contentResolver.delete(android.content.ContentUris.withAppendedId(collection, id), null, null)
        }
    }

    @Test
    fun downloads_saver_copies_the_file_and_a_second_save_replaces_it() = runBlocking {
        val saver = DownloadsSaver(context)
        val first = File(context.cacheDir, "first.bin").also { created += it; it.writeText("a much longer first version of the file") }
        val second = File(context.cacheDir, "second.bin").also { created += it; it.writeText("short") }

        val location = saver.save(first, "androidtest-doc.pdf", AppFiles.MIME_PDF)
        assertEquals("Download/FergBentables/androidtest-doc.pdf", location)
        val rows = downloadsRows("androidtest-doc.pdf")
        assertEquals(1, rows.size)

        saver.save(second, "androidtest-doc.pdf", AppFiles.MIME_PDF)
        val after = downloadsRows("androidtest-doc.pdf")
        assertEquals("saving the same name again must replace, not duplicate", 1, after.size)

        val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), after.single().first)
        val text = context.contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) }
        assertEquals("the longer first version must leave no trailing bytes behind", "short", text)
        assertNotNull(location)
    }
}
