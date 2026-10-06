package com.blushdesk.app.ui.showroom

import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.blushdesk.app.data.local.database.Buyer
import com.blushdesk.app.data.local.database.BuyerListItem
import com.blushdesk.app.data.local.database.OperatorProfile
import com.blushdesk.app.data.local.database.Order
import com.blushdesk.app.data.local.database.OrderItem
import com.blushdesk.app.data.local.database.ShowroomSummary
import com.blushdesk.app.di.AppContainer
import com.blushdesk.app.domain.model.BuyerDetail
import com.blushdesk.app.domain.model.FulfillmentStatus
import com.blushdesk.app.domain.model.PaymentStatus
import com.blushdesk.app.domain.model.UserFacingException
import com.blushdesk.app.domain.repository.ShowroomRepository
import com.blushdesk.app.utils.DocumentService
import com.blushdesk.app.utils.ExportDocument
import com.blushdesk.app.utils.PhotoStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Holds the screen state and runs every user action: buyer selection and search, buyer and order
 * CRUD, payment and fulfillment changes, the operator profile, PDF receipts and the Excel export.
 *
 * The screen renders [uiState] and calls these functions; it never touches the database. Results
 * arrive as [events]. Failures become friendly messages: a [UserFacingException] is shown as
 * written, anything else is logged and replaced with a generic sentence, so raw errors never
 * reach the operator.
 *
 * The selected buyer and order are kept in [savedState] as well, so they survive Android closing
 * the app in the background. A form reopened after that has to belong to the same buyer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShowroomViewModel(
    private val repository: ShowroomRepository,
    private val photos: PhotoStore,
    private val documents: DocumentService,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val selectedBuyerId = MutableStateFlow(savedState.get<Long>(KEY_BUYER))
    private val selectedOrderId = MutableStateFlow(savedState.get<Long>(KEY_ORDER))

    init {
        viewModelScope.launch { selectedBuyerId.collect { savedState[KEY_BUYER] = it } }
        viewModelScope.launch { selectedOrderId.collect { savedState[KEY_ORDER] = it } }
    }
    private val busy = MutableStateFlow(Busy())

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    private val buyers: Flow<List<BuyerListItem>> = query.flatMapLatest { repository.searchBuyers(it) }

    private val detail: Flow<BuyerDetail?> = selectedBuyerId.flatMapLatest { id ->
        if (id == null) {
            flowOf(null)
        } else {
            combine(repository.observeBuyer(id), repository.observeOrders(id), repository.observeOrderTotals(id)) { buyer, orders, totals ->
                buyer?.let { BuyerDetail(it, orders, totals) }
            }.onEach { loaded ->
                // The selected buyer was deleted: fall back to "nothing selected".
                if (loaded == null) selectedBuyerId.compareAndSet(id, null)
            }
        }
    }

    private data class Core(val operator: OperatorProfile, val summary: ShowroomSummary, val buyers: List<BuyerListItem>)
    private data class Selection(val query: String, val buyerId: Long?, val detail: BuyerDetail?, val orderId: Long?, val busy: Busy)
    private data class Busy(val exporting: Boolean = false, val receiptOrderId: Long? = null)

    val uiState: StateFlow<ShowroomUiState> = combine(
        combine(repository.operator, repository.summary, buyers, ::Core),
        combine(query, selectedBuyerId, detail, selectedOrderId, busy, ::Selection),
    ) { core, selection ->
        ShowroomUiState(
            operator = core.operator,
            operatorLoaded = true,
            summary = core.summary,
            query = selection.query,
            buyers = core.buyers,
            buyersLoaded = true,
            selectedBuyerId = selection.buyerId,
            detail = selection.detail,
            selectedOrderId = selection.orderId,
            exporting = selection.busy.exporting,
            receiptOrderId = selection.busy.receiptOrderId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ShowroomUiState())

    init {
        // With nobody selected (first launch, or the selected buyer was deleted) show the first
        // buyer in the list rather than an empty detail pane.
        viewModelScope.launch {
            combine(selectedBuyerId, buyers) { id, list -> id to list }.collect { (id, list) ->
                if (id == null && list.isNotEmpty()) selectedBuyerId.compareAndSet(null, list.first().buyer.id)
            }
        }
    }

    // ---- Selection and search ---------------------------------------------------------------

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun selectBuyer(buyerId: Long) {
        if (selectedBuyerId.value != buyerId) selectedOrderId.value = null
        selectedBuyerId.value = buyerId
    }

    /** Expands one order in the history. */
    fun selectOrder(orderId: Long) {
        selectedOrderId.value = orderId
    }

    // ---- Operator ---------------------------------------------------------------------------

    fun saveOperator(profile: OperatorProfile) = act("Couldn't save your profile. Please try again.") {
        repository.saveOperator(profile)
        emitSuccess("Profile saved")
    }

    // ---- Buyers -----------------------------------------------------------------------------

    fun saveBuyer(buyer: Buyer) = act("Couldn't save the buyer. Please try again.") {
        val isNew = buyer.id == 0L
        val id = repository.saveBuyer(buyer)
        if (isNew) selectBuyer(id)
        emitSuccess(if (isNew) "Buyer added" else "Buyer updated")
    }

    fun deleteBuyer(buyerId: Long) = act("Couldn't delete the buyer. Please try again.") {
        repository.deleteBuyer(buyerId)
        emitSuccess("Buyer and their orders deleted")
    }

    // ---- Orders -----------------------------------------------------------------------------

    fun saveOrder(order: Order, items: List<OrderItem>) = act("Couldn't save the order. Please try again.") {
        val isNew = order.id == 0L
        val id = repository.saveOrder(order, items)
        if (isNew) selectedOrderId.value = id
        emitSuccess(if (isNew) "Order added" else "Order updated")
    }

    fun deleteOrder(orderId: Long) = act("Couldn't delete the order. Please try again.") {
        repository.deleteOrder(orderId)
        emitSuccess("Order deleted")
    }

    /** One tap on "Move to <next stage>": always exactly one stage forward, never a skip. */
    fun advanceOrder(order: Order) {
        val next = order.fulfillmentStatus.next ?: return
        setFulfillmentStatus(order.id, next)
    }

    fun setFulfillmentStatus(orderId: Long, status: FulfillmentStatus) =
        act("Couldn't update the order. Please try again.") { repository.setFulfillmentStatus(orderId, status) }

    fun setPaymentStatus(orderId: Long, status: PaymentStatus) =
        act("Couldn't update the payment. Please try again.") { repository.setPaymentStatus(orderId, status) }

    // ---- Photos -----------------------------------------------------------------------------

    /** Copies a picked or captured image into private storage. Returns its URI, or null on failure. */
    suspend fun importPhoto(source: Uri): String? = try {
        photos.importPhoto(source)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Photo import failed", e)
        emitError("Couldn't use that photo. Try a different picture.")
        null
    }

    /** Drops a photo that was picked in a dialog but then replaced or cancelled. */
    fun discardPhoto(imageUri: String?) = photos.delete(imageUri)

    // ---- Documents --------------------------------------------------------------------------

    fun generateReceipt(orderId: Long) {
        if (busy.value.receiptOrderId != null) return
        busy.update { it.copy(receiptOrderId = orderId) }
        act("Couldn't create the receipt. Please try again.") {
            try {
                _events.send(UiEvent.ReceiptReady(documents.createReceipt(orderId)))
            } finally {
                busy.update { it.copy(receiptOrderId = null) }
            }
        }
    }

    /** Builds the workbook and hands it to the share sheet (email, Drive, Messenger, ...). */
    fun shareExcel() = export("Couldn't create the Excel file. Please try again.") { workbook ->
        _events.send(UiEvent.ShareWorkbook(workbook))
    }

    /** Builds the workbook and saves it in Download/<APP_NAME> on this device. */
    fun downloadExcel() = export("Couldn't save the Excel file to this device. Please try again.") { workbook ->
        _events.send(UiEvent.WorkbookSaved(workbook, savedTo = documents.saveToDownloads(workbook)))
    }

    private fun export(failure: String, deliver: suspend (ExportDocument) -> Unit) {
        if (busy.value.exporting) return
        busy.update { it.copy(exporting = true) }
        act(failure) {
            try {
                deliver(documents.createWorkbook())
            } finally {
                busy.update { it.copy(exporting = false) }
            }
        }
    }

    /** For the screen to report problems it detects itself (no app to share with, ...). */
    fun reportError(message: String) = emitError(message)

    // ---- Plumbing ---------------------------------------------------------------------------

    private fun emitSuccess(message: String) {
        _events.trySend(UiEvent.Success(message))
    }

    private fun emitError(message: String) {
        _events.trySend(UiEvent.Error(message))
    }

    /** Runs [block] and turns any failure into a friendly error message instead of a crash. */
    private fun act(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserFacingException) {
                emitError(e.message ?: failure)
            } catch (e: Exception) {
                Log.e(TAG, failure, e)
                emitError(failure)
            }
        }
    }

    companion object {
        private const val TAG = "ShowroomViewModel"
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val KEY_BUYER = "selectedBuyerId"
        private const val KEY_ORDER = "selectedOrderId"

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ShowroomViewModel(container.repository, container.photoStorage, container.documents, createSavedStateHandle())
            }
        }
    }
}
