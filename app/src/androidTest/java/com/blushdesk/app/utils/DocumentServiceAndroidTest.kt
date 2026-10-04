package com.blushdesk.app.utils

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blushdesk.app.TestData
import com.blushdesk.app.data.local.database.AppDatabase
import com.blushdesk.app.data.repository.OfflineShowroomRepository
import com.blushdesk.app.domain.model.PaymentStatus
import com.blushdesk.app.domain.model.UserFacingException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The whole export path with the real pieces (Room, repository, PDF, POI, MediaStore) plus the
 * FileProvider configuration that lets other apps read the results.
 */
@RunWith(AndroidJUnit4::class)
class DocumentServiceAndroidTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var service: AndroidDocumentService
    private lateinit var repository: OfflineShowroomRepository

    @Before
    fun setUp() {
        clearGeneratedFiles() // start from an empty cache, whatever earlier runs or manual use left behind
        db = TestData.inMemoryDatabase(context)
        repository = OfflineShowroomRepository(db, PhotoStorage(context))
        service = AndroidDocumentService(context, repository, ExcelExporter(), PdfReceiptGenerator(), DownloadsSaver(context))
    }

    private fun clearGeneratedFiles() {
        AppFiles.cacheDir(context, AppFiles.RECEIPTS_DIR).listFiles()?.forEach { it.delete() }
        AppFiles.cacheDir(context, AppFiles.EXPORTS_DIR).listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        db.close()
        clearGeneratedFiles()
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        context.contentResolver.query(
            collection, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf("Receipt_BD-%"), null,
        )?.use { c -> while (c.moveToNext()) context.contentResolver.delete(ContentUris.withAppendedId(collection, c.getLong(0)), null, null) }
    }

    private fun seed(paid: PaymentStatus): Pair<Long, Long> = runBlocking {
        repository.saveOperator(TestData.operator)
        val buyerId = repository.saveBuyer(TestData.buyer("Ana Reyes"))
        val orderId = repository.saveOrder(TestData.order(buyerId, pay = paid), listOf(TestData.item(), TestData.item("Throw Pillow", "450", 4)))
        buyerId to orderId
    }

    @Test
    fun receipt_for_a_paid_order_is_created_saved_to_downloads_and_readable_through_the_file_provider() = runBlocking {
        val (_, orderId) = seed(PaymentStatus.PAID)

        val receipt = service.createReceipt(orderId)

        assertTrue(receipt.file.exists())
        assertEquals("Receipt_BD-${orderId.toString().padStart(6, '0')}_Ana_Reyes.pdf", receipt.displayName)
        assertEquals("Download/FergBentables/${receipt.displayName}", receipt.savedTo)

        // Another app would read it through this URI; prove the FileProvider config really serves it.
        val uri = AppFiles.uriFor(context, receipt.file)
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.fileprovider", uri.authority)
        val header = context.contentResolver.openInputStream(uri)!!.use { String(it.readNBytes(4)) }
        assertEquals("%PDF", header)
    }

    @Test
    fun receipt_for_an_unpaid_order_is_refused_with_a_clear_message() {
        val (_, orderId) = seed(PaymentStatus.PENDING)
        try {
            runBlocking { service.createReceipt(orderId) }
            fail("expected a refusal")
        } catch (e: UserFacingException) {
            assertTrue(e.message!!, e.message!!.contains("paid"))
        }
        assertEquals(0, AppFiles.cacheDir(context, AppFiles.RECEIPTS_DIR).listFiles()?.size ?: 0)
    }

    @Test
    fun regenerating_a_receipt_reuses_the_same_files() = runBlocking {
        val (_, orderId) = seed(PaymentStatus.PAID)
        val first = service.createReceipt(orderId)
        val second = service.createReceipt(orderId)
        assertEquals(first.file, second.file)
        assertEquals(1, AppFiles.cacheDir(context, AppFiles.RECEIPTS_DIR).listFiles()?.size)
    }

    @Test
    fun workbook_contains_what_is_in_the_database_and_is_shareable() = runBlocking {
        seed(PaymentStatus.PAID)

        val export = service.createWorkbook()

        assertTrue(export.displayName.matches(Regex("FergBentables_Export_\\d{4}-\\d{2}-\\d{2}_\\d{4}\\.xlsx")))
        val uri = AppFiles.uriFor(context, export.file)
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertEquals("PK", String(bytes, 0, 2)) // a zip container, which is what .xlsx is
        assertTrue(bytes.size > 2_000)
    }

    @Test
    fun exporting_an_empty_database_is_refused_with_a_clear_message() {
        try {
            runBlocking { service.createWorkbook() }
            fail("expected a refusal")
        } catch (e: UserFacingException) {
            assertTrue(e.message!!, e.message!!.contains("nothing to export"))
        }
    }

    @Test
    fun file_provider_refuses_files_outside_the_shared_folders() {
        val secret = File(context.cacheDir, "secret/token.txt").also { it.parentFile!!.mkdirs(); it.writeText("do not share") }
        try {
            AppFiles.uriFor(context, secret)
            fail("the cache root must not be shareable")
        } catch (_: IllegalArgumentException) {
            // expected: file_paths.xml only exposes receipts/, exports/ and camera/
        } finally {
            secret.parentFile!!.deleteRecursively()
        }
        val db = File(context.getDatabasePath("showroom.db").path)
        try {
            AppFiles.uriFor(context, db)
            fail("the database must not be shareable")
        } catch (_: IllegalArgumentException) {
        }
        assertNotNull(db)
        assertFalse(db.path.contains("cache"))
    }
}
