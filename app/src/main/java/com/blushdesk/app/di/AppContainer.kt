package com.blushdesk.app.di

import android.content.Context
import com.blushdesk.app.data.local.database.AppDatabase
import com.blushdesk.app.data.repository.OfflineShowroomRepository
import com.blushdesk.app.domain.repository.ShowroomRepository
import com.blushdesk.app.utils.AndroidDocumentService
import com.blushdesk.app.utils.DocumentService
import com.blushdesk.app.utils.DownloadsSaver
import com.blushdesk.app.utils.ExcelExporter
import com.blushdesk.app.utils.PdfReceiptGenerator
import com.blushdesk.app.utils.PhotoStorage

/**
 * Hand-rolled dependency container, created once by [com.blushdesk.app.BlushDeskApp].
 *
 * A dependency-injection framework would add a code generator for what is, here, six objects.
 * Everything is `lazy`, so nothing is built (and the database is not opened) until first use.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.create(appContext) }

    val photoStorage: PhotoStorage by lazy { PhotoStorage(appContext) }

    val repository: ShowroomRepository by lazy { OfflineShowroomRepository(database, photoStorage) }

    val documents: DocumentService by lazy {
        AndroidDocumentService(
            context = appContext,
            repository = repository,
            excel = ExcelExporter(),
            pdf = PdfReceiptGenerator(),
            downloads = DownloadsSaver(appContext),
        )
    }
}
