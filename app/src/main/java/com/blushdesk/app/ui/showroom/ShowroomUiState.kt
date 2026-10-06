package com.blushdesk.app.ui.showroom

import com.blushdesk.app.data.local.database.BuyerListItem
import com.blushdesk.app.data.local.database.OperatorProfile
import com.blushdesk.app.data.local.database.ShowroomSummary
import com.blushdesk.app.domain.model.BuyerDetail
import com.blushdesk.app.utils.ExportDocument
import com.blushdesk.app.utils.ReceiptDocument
import java.math.BigDecimal

/** Everything the screen renders, as one immutable snapshot. */
data class ShowroomUiState(
    val operator: OperatorProfile = OperatorProfile(),
    /** False until the first database read, so the first-run profile prompt does not flash. */
    val operatorLoaded: Boolean = false,
    val summary: ShowroomSummary = EMPTY_SUMMARY,
    val query: String = "",
    val buyers: List<BuyerListItem> = emptyList(),
    val buyersLoaded: Boolean = false,
    val selectedBuyerId: Long? = null,
    val detail: BuyerDetail? = null,
    /** The order the user opened; null means "the newest one". */
    val selectedOrderId: Long? = null,
    val exporting: Boolean = false,
    /** Id of the order whose receipt is being generated, if any. */
    val receiptOrderId: Long? = null,
) {
    val isLoading: Boolean get() = !operatorLoaded || !buyersLoaded

    /** The order shown in full in the history: the selected one if it still exists, else the newest. */
    val expandedOrderId: Long?
        get() {
            val orders = detail?.orders ?: return null
            return selectedOrderId?.takeIf { id -> orders.any { it.order.id == id } } ?: orders.firstOrNull()?.order?.id
        }

    companion object {
        val EMPTY_SUMMARY = ShowroomSummary(
            buyerCount = 0,
            orderCount = 0,
            openOrders = 0,
            paidAmount = BigDecimal.ZERO.setScale(2),
            outstandingAmount = BigDecimal.ZERO.setScale(2),
        )
    }
}

/** One-shot results the screen handles once: messages, a ready receipt, a workbook to share or one just saved. */
sealed interface UiEvent {
    data class Success(val message: String) : UiEvent
    data class Error(val message: String) : UiEvent
    data class ReceiptReady(val receipt: ReceiptDocument) : UiEvent
    data class ShareWorkbook(val export: ExportDocument) : UiEvent
    /** The workbook was copied to Downloads; [savedTo] is "Download/<APP_NAME>/<name>". */
    data class WorkbookSaved(val export: ExportDocument, val savedTo: String) : UiEvent
}
