package com.blushdesk.app.ui.showroom

import android.database.sqlite.SQLiteException
import androidx.lifecycle.SavedStateHandle
import com.blushdesk.app.data.local.database.Buyer
import com.blushdesk.app.data.local.database.Order
import com.blushdesk.app.data.local.database.OrderItem
import com.blushdesk.app.domain.model.FulfillmentStatus
import com.blushdesk.app.domain.model.PaymentMode
import com.blushdesk.app.domain.model.PaymentStatus
import com.blushdesk.app.domain.model.UserFacingException
import com.blushdesk.app.utils.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ShowroomViewModelTest {

    private lateinit var repo: FakeRepository
    private lateinit var docs: FakeDocuments
    private lateinit var photos: FakePhotos
    private lateinit var vm: ShowroomViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repo = FakeRepository()
        docs = FakeDocuments()
        photos = FakePhotos()
        vm = ShowroomViewModel(repo, photos, docs)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buyer(name: String, contact: String = "0917 000 0000") =
        Buyer(fullName = name, contactNumber = contact, dateAdded = Instant.parse("2026-10-01T00:00:00Z"))

    /** An order header plus its product lines, as the order form hands them over. */
    private class NewOrder(val order: Order, val items: List<OrderItem>)

    private fun item(product: String = "Sofa", unit: String = "1000.00", qty: Int = 1) =
        OrderItem(productName = product, unitPrice = Money.of(unit), quantity = qty)

    private fun order(
        buyerId: Long,
        product: String = "Sofa",
        unit: String = "1000.00",
        qty: Int = 1,
        pay: PaymentStatus = PaymentStatus.PENDING,
        stage: FulfillmentStatus = FulfillmentStatus.PROCESSING,
        at: String = "2026-10-01T00:00:00Z",
        items: List<OrderItem> = listOf(item(product, unit, qty)),
    ) = NewOrder(
        Order(
            buyerId = buyerId, purchaseDateTime = Instant.parse(at), paymentMode = PaymentMode.CASH,
            paymentStatus = pay, fulfillmentStatus = stage,
        ),
        items,
    )

    private suspend fun FakeRepository.saveOrder(new: NewOrder) = saveOrder(new.order, new.items)
    private fun ShowroomViewModel.saveOrder(new: NewOrder) = saveOrder(new.order, new.items)

    /** Keeps uiState and the event stream hot for the duration of the test. */
    private fun TestScope.observe(): MutableList<UiEvent> {
        val events = mutableListOf<UiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        return events
    }

    // ---- Selection ---------------------------------------------------------------------------

    @Test
    fun `on start the alphabetically first buyer is selected`() = runTest {
        repo.saveBuyer(buyer("Zed"))
        repo.saveBuyer(buyer("Ana"))
        vm = ShowroomViewModel(repo, photos, docs)
        observe()
        advanceUntilIdle()

        assertEquals(2L, vm.uiState.value.selectedBuyerId)
        assertEquals("Ana", vm.uiState.value.detail?.buyer?.fullName)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `the selection survives Android closing the app in the background`() = runTest {
        repo.saveBuyer(buyer("Ana"))
        val ben = repo.saveBuyer(buyer("Ben"))
        val bensOrder = repo.saveOrder(order(ben))
        val saved = SavedStateHandle()
        vm = ShowroomViewModel(repo, photos, docs, saved)
        observe()
        vm.selectBuyer(ben)
        vm.selectOrder(bensOrder)
        advanceUntilIdle()

        // A new process gets a new view model, built from what the old one saved.
        vm = ShowroomViewModel(repo, photos, docs, SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        observe()
        advanceUntilIdle()

        assertEquals(ben, vm.uiState.value.selectedBuyerId)
        assertEquals("Ben", vm.uiState.value.detail?.buyer?.fullName)
        assertEquals(bensOrder, vm.uiState.value.selectedOrderId)
    }

    @Test
    fun `selection picks up the first buyer ever added and then stays put`() = runTest {
        observe()
        assertNull(vm.uiState.value.selectedBuyerId)

        repo.saveBuyer(buyer("Zed"))
        advanceUntilIdle()
        assertEquals(1L, vm.uiState.value.selectedBuyerId)

        repo.saveBuyer(buyer("Ana")) // sorts first, but must not steal the selection
        advanceUntilIdle()
        assertEquals(1L, vm.uiState.value.selectedBuyerId)
    }

    @Test
    fun `search filters the list but keeps the selected buyer's detail`() = runTest {
        observe()
        repo.saveBuyer(buyer("Ana Reyes"))
        repo.saveBuyer(buyer("Ben Cruz"))
        advanceUntilIdle()
        vm.selectBuyer(1)

        vm.onQueryChange("cruz")
        advanceUntilIdle()

        assertEquals(listOf("Ben Cruz"), vm.uiState.value.buyers.map { it.buyer.fullName })
        assertEquals("Ana Reyes", vm.uiState.value.detail?.buyer?.fullName)
    }

    @Test
    fun `the newest order is expanded until another is selected`() = runTest {
        observe()
        val id = repo.saveBuyer(buyer("Ana"))
        val older = repo.saveOrder(order(id, product = "Old", at = "2026-09-01T00:00:00Z"))
        val newer = repo.saveOrder(order(id, product = "New", at = "2026-10-01T00:00:00Z"))
        advanceUntilIdle()

        assertEquals(newer, vm.uiState.value.expandedOrderId)
        vm.selectOrder(older)
        advanceUntilIdle()
        assertEquals(older, vm.uiState.value.expandedOrderId)
    }

    @Test
    fun `switching buyers resets the expanded order`() = runTest {
        observe()
        val ana = repo.saveBuyer(buyer("Ana"))
        val ben = repo.saveBuyer(buyer("Ben"))
        repo.saveOrder(order(ana, at = "2026-09-01T00:00:00Z"))
        val anaNewest = repo.saveOrder(order(ana, at = "2026-10-01T00:00:00Z"))
        val bensOrder = repo.saveOrder(order(ben))
        vm.selectBuyer(ana)
        vm.selectOrder(1)

        vm.selectBuyer(ben)
        advanceUntilIdle()
        assertEquals(bensOrder, vm.uiState.value.expandedOrderId)

        vm.selectBuyer(ana)
        advanceUntilIdle()
        assertEquals(anaNewest, vm.uiState.value.expandedOrderId)
    }

    // ---- Buyers and orders --------------------------------------------------------------------

    @Test
    fun `saving a new buyer selects it and reports success`() = runTest {
        val events = observe()
        repo.saveBuyer(buyer("Ana"))

        vm.saveBuyer(buyer("Ben"))
        advanceUntilIdle()

        assertEquals("Ben", vm.uiState.value.detail?.buyer?.fullName)
        assertTrue(events.any { it is UiEvent.Success && it.message == "Buyer added" })
    }

    @Test
    fun `a new order is selected and its total is calculated, not typed`() = runTest {
        observe()
        val id = repo.saveBuyer(buyer("Ana"))
        advanceUntilIdle()

        // A line total that disagrees with price x quantity is ignored and recomputed.
        vm.saveOrder(order(id, items = listOf(item(unit = "999.99", qty = 3).copy(lineTotal = Money.of("1.00")))))
        advanceUntilIdle()

        val saved = vm.uiState.value.detail!!.orders.single()
        assertEquals(BigDecimal("2999.97"), saved.order.totalAmount)
        assertEquals(BigDecimal("2999.97"), saved.items.single().lineTotal)
        assertEquals(saved.order.id, vm.uiState.value.expandedOrderId)
    }

    @Test
    fun `an order with several products totals all its lines`() = runTest {
        observe()
        val id = repo.saveBuyer(buyer("Ana"))

        vm.saveOrder(order(id, items = listOf(item("Sofa", "12500.50", 1), item("Pillow", "450.00", 4), item("Rug", "3200.00", 1))))
        advanceUntilIdle()

        val saved = vm.uiState.value.detail!!.orders.single()
        assertEquals(listOf("Sofa", "Pillow", "Rug"), saved.items.map { it.productName })
        assertEquals(BigDecimal("17500.50"), saved.order.totalAmount)
        assertEquals(6, saved.unitCount)
        assertEquals(BigDecimal("17500.50"), vm.uiState.value.detail!!.totals.outstandingAmount)
    }

    @Test
    fun `an order without products is refused with a clear message`() = runTest {
        val events = observe()
        val id = repo.saveBuyer(buyer("Ana"))

        vm.saveOrder(order(id, items = emptyList()))
        advanceUntilIdle()

        assertEquals("Add at least one product", (events.single() as UiEvent.Error).message)
        assertEquals(0, repo.orderCount())
    }

    @Test
    fun `dashboard totals add up paid and outstanding orders`() = runTest {
        observe()
        val id = repo.saveBuyer(buyer("Ana"))
        repo.saveOrder(order(id, unit = "2500.00", qty = 2, pay = PaymentStatus.PAID, stage = FulfillmentStatus.DELIVERED))
        repo.saveOrder(order(id, unit = "1000.00", qty = 1, pay = PaymentStatus.PENDING))
        repo.saveOrder(order(id, unit = "500.00", qty = 3, pay = PaymentStatus.UNPAID, stage = FulfillmentStatus.PREPARING))
        advanceUntilIdle()

        val totals = vm.uiState.value.detail!!.totals
        assertEquals(3, totals.orderCount)
        assertEquals(2, totals.openOrders)
        assertEquals(BigDecimal("5000.00"), totals.paidAmount)
        assertEquals(BigDecimal("2500.00"), totals.outstandingAmount)
    }

    @Test
    fun `advancing walks processing to preparing to delivered and stops`() = runTest {
        observe()
        val buyerId = repo.saveBuyer(buyer("Ana"))
        val orderId = repo.saveOrder(order(buyerId))

        vm.advanceOrder(repo.getOrder(orderId)!!.order)
        advanceUntilIdle()
        assertEquals(FulfillmentStatus.PREPARING, repo.getOrder(orderId)!!.order.fulfillmentStatus)

        vm.advanceOrder(repo.getOrder(orderId)!!.order)
        advanceUntilIdle()
        assertEquals(FulfillmentStatus.DELIVERED, repo.getOrder(orderId)!!.order.fulfillmentStatus)

        vm.advanceOrder(repo.getOrder(orderId)!!.order)
        advanceUntilIdle()
        assertEquals(FulfillmentStatus.DELIVERED, repo.getOrder(orderId)!!.order.fulfillmentStatus)
    }

    @Test
    fun `a stage can be set directly, including going back`() = runTest {
        observe()
        val buyerId = repo.saveBuyer(buyer("Ana"))
        val orderId = repo.saveOrder(order(buyerId, stage = FulfillmentStatus.DELIVERED))

        vm.setFulfillmentStatus(orderId, FulfillmentStatus.PROCESSING)
        advanceUntilIdle()

        assertEquals(FulfillmentStatus.PROCESSING, repo.getOrder(orderId)!!.order.fulfillmentStatus)
    }

    @Test
    fun `deleting the selected buyer removes their orders and selects the next buyer`() = runTest {
        observe()
        val ana = repo.saveBuyer(buyer("Ana"))
        val ben = repo.saveBuyer(buyer("Ben"))
        repo.saveOrder(order(ana))
        advanceUntilIdle()
        vm.selectBuyer(ana)

        vm.deleteBuyer(ana)
        advanceUntilIdle()

        assertEquals(0, repo.orderCount())
        assertEquals(ben, vm.uiState.value.selectedBuyerId)
    }

    @Test
    fun `deleting the last buyer leaves nothing selected`() = runTest {
        observe()
        val only = repo.saveBuyer(buyer("Ana"))
        advanceUntilIdle()

        vm.deleteBuyer(only)
        advanceUntilIdle()

        assertNull(vm.uiState.value.selectedBuyerId)
        assertNull(vm.uiState.value.detail)
    }

    // ---- Errors are friendly ------------------------------------------------------------------

    @Test
    fun `a rule broken by the user is shown with its own message`() = runTest {
        val events = observe()

        vm.saveBuyer(buyer("Ana", contact = "123"))
        advanceUntilIdle()

        assertEquals("Enter a valid phone number", (events.single() as UiEvent.Error).message)
    }

    @Test
    fun `a technical failure is reported generically, never with the raw error text`() = runTest {
        val events = observe()
        repo.failSavesWith = SQLiteException("disk I/O error (code 266 SQLITE_IOERR_READ)")
        val buyerId = 1L

        vm.saveOrder(order(buyerId))
        advanceUntilIdle()

        val message = (events.single() as UiEvent.Error).message
        assertEquals("Couldn't save the order. Please try again.", message)
        assertFalse(message.contains("SQLITE"))
    }

    // ---- Documents ----------------------------------------------------------------------------

    @Test
    fun `receipt for a paid order raises ReceiptReady and clears the busy flag`() = runTest {
        val events = observe()
        val buyerId = repo.saveBuyer(buyer("Ana"))
        val orderId = repo.saveOrder(order(buyerId, pay = PaymentStatus.PAID))

        vm.generateReceipt(orderId)
        advanceUntilIdle()

        assertEquals(listOf(orderId), docs.receiptsRequested)
        assertEquals("receipt_$orderId.pdf", events.filterIsInstance<UiEvent.ReceiptReady>().single().receipt.displayName)
        assertNull(vm.uiState.value.receiptOrderId)
    }

    @Test
    fun `a refused receipt shows the reason and does not stay busy`() = runTest {
        val events = observe()
        docs.failure = UserFacingException("Mark the order as paid before issuing a receipt")

        vm.generateReceipt(7)
        advanceUntilIdle()

        assertEquals("Mark the order as paid before issuing a receipt", (events.single() as UiEvent.Error).message)
        assertNull(vm.uiState.value.receiptOrderId)
    }

    @Test
    fun `excel export shares the workbook and clears the exporting flag`() = runTest {
        val events = observe()

        vm.exportToExcel()
        advanceUntilIdle()

        assertEquals(1, docs.workbooksCreated)
        assertEquals("export.xlsx", events.filterIsInstance<UiEvent.ShareWorkbook>().single().export.displayName)
        assertFalse(vm.uiState.value.exporting)
    }

    @Test
    fun `a crashed export is reported generically and the button works again`() = runTest {
        val events = observe()
        docs.failure = IllegalStateException("org.apache.poi.ooxml.POIXMLException: boom")

        vm.exportToExcel()
        advanceUntilIdle()

        assertEquals("Couldn't create the Excel file. Please try again.", (events.single() as UiEvent.Error).message)
        assertFalse(vm.uiState.value.exporting)
    }

    // ---- Operator and photos ------------------------------------------------------------------

    @Test
    fun `saving the operator profile stores it and reports success`() = runTest {
        val events = observe()
        vm.saveOperator(repo.getOperator().copy(fullName = "Lia Santos", storeName = "Rosé Showroom"))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.operator.isSetUp)
        assertTrue(events.single() is UiEvent.Success)
    }

    @Test
    fun `discarding a photo asks the store to delete it`() {
        vm.discardPhoto("file:///data/photos/a.jpg")
        assertEquals(listOf<String?>("file:///data/photos/a.jpg"), photos.discarded)
    }
}
