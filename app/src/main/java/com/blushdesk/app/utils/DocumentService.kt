package com.blushdesk.app.utils

import android.content.Context
import android.util.Log
import com.blushdesk.app.domain.model.UserFacingException
import com.blushdesk.app.domain.repository.ShowroomRepository
import kotlinx.coroutines.CancellationException
import java.io.File

/** A generated receipt: the local file to open or share, and where its Downloads copy landed. */
data class ReceiptDocument(
    val file: File,
    val displayName: String,
    /** "Download/BlushDesk/<name>", or null if the copy to Downloads failed (the local file is still good). */
    val savedTo: String?,
)

/** A generated workbook, saved locally and ready for the share sheet. */
data class ExportDocument(val file: File, val displayName: String)

/**
 * Produces the two kinds of file the app creates. Behind an interface so the ViewModel can be
 * unit tested without a device: the real implementation needs PdfDocument and MediaStore.
 */
interface DocumentService {
    /** The PDF receipt of a paid order, also copied to Downloads. */
    suspend fun createReceipt(orderId: Long): ReceiptDocument

    /** The .xlsx with every record in the database. */
    suspend fun createWorkbook(): ExportDocument
}

class AndroidDocumentService(
    private val context: Context,
    private val repository: ShowroomRepository,
    private val excel: ExcelExporter,
    private val pdf: PdfReceiptGenerator,
    private val downloads: DownloadsSaver,
) : DocumentService {

    override suspend fun createReceipt(orderId: Long): ReceiptDocument {
        val order = repository.getOrder(orderId) ?: throw UserFacingException("That order no longer exists")
        if (!order.order.paymentStatus.isPaid) throw UserFacingException("Mark the order as paid before issuing a receipt")
        val buyer = repository.getBuyer(order.order.buyerId) ?: throw UserFacingException("That buyer no longer exists")
        val operator = repository.getOperator()

        val folder = AppFiles.cacheDir(context, AppFiles.RECEIPTS_DIR)
        val name = "Receipt_${Formats.orderNumber(order.order.id)}_${Formats.fileSafe(buyer.fullName)}.pdf"
        val file = pdf.generate(File(folder, name), operator, buyer, order)

        // The receipt is already complete in the cache; failing to copy it to Downloads must not lose it.
        val savedTo = try {
            downloads.save(file, name, AppFiles.MIME_PDF)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy receipt to Downloads", e)
            null
        }
        AppFiles.prune(folder, keep = 20)
        return ReceiptDocument(file, name, savedTo)
    }

    override suspend fun createWorkbook(): ExportDocument {
        val snapshot = repository.getExportSnapshot()
        if (snapshot.buyers.isEmpty()) throw UserFacingException("There is nothing to export yet. Add a buyer first.")

        val folder = AppFiles.cacheDir(context, AppFiles.EXPORTS_DIR)
        val name = "BlushDesk_Export_${Formats.fileStamp(snapshot.takenAt)}.xlsx"
        val file = excel.export(File(folder, name), snapshot)
        AppFiles.prune(folder, keep = 5)
        return ExportDocument(file, name)
    }

    private companion object {
        const val TAG = "DocumentService"
    }
}
