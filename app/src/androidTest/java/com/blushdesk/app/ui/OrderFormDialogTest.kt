package com.blushdesk.app.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blushdesk.app.data.local.database.Order
import com.blushdesk.app.data.local.database.OrderItem
import com.blushdesk.app.data.local.database.OrderWithItems
import com.blushdesk.app.domain.model.PaymentMode
import com.blushdesk.app.domain.model.PaymentStatus
import com.blushdesk.app.ui.order.AddOrderDialog
import com.blushdesk.app.ui.order.EditOrderDialog
import com.blushdesk.app.ui.theme.ShowroomPinkTheme
import com.blushdesk.app.utils.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** The order form on its own: product lines, live totals, what it hands over, and closing it. */
@RunWith(AndroidJUnit4::class)
class OrderFormDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private var saved: Pair<Order, List<OrderItem>>? = null
    private var dismissed = false

    private fun showNewOrderForm() = compose.setContent {
        ShowroomPinkTheme {
            AddOrderDialog(buyerId = 7, buyerName = "Ana Reyes", onSave = { order, items -> saved = order to items }, onDismiss = { dismissed = true })
        }
    }

    private fun showEditForm() {
        val existing = OrderWithItems(
            Order(
                id = 3, buyerId = 7, totalAmount = Money.of("6800.00"), purchaseDateTime = Instant.parse("2026-10-02T12:57:00Z"),
                paymentMode = PaymentMode.CASH, paymentStatus = PaymentStatus.PAID,
            ),
            listOf(OrderItem(id = 1, orderId = 3, productName = "Rattan Coffee Table", unitPrice = Money.of("6800.00"), quantity = 1)),
        )
        compose.setContent {
            ShowroomPinkTheme {
                EditOrderDialog(order = existing, buyerName = "Ana Reyes", onSave = { order, items -> saved = order to items }, onDismiss = { dismissed = true })
            }
        }
    }

    private fun nameField(line: Int) = compose.onAllNodesWithText("Product name")[line]
    private fun priceField(line: Int) = compose.onAllNodesWithText("Unit price")[line]
    private fun addLine() = compose.onNodeWithText("Add another product", substring = true).performScrollTo().performClick()
    private fun save() = compose.onNode(hasText("Add order") and hasClickAction()).performClick()
    private fun cancel() = compose.onNode(hasText("Cancel") and hasClickAction()).performClick()

    @Test
    fun a_new_line_is_scrolled_into_view_with_the_cursor_in_its_name() {
        showNewOrderForm()
        repeat(3) { addLine() }

        compose.onNodeWithText("Product 4").assertIsDisplayed()
        nameField(3).assertIsDisplayed().assertIsFocused()
    }

    @Test
    fun line_and_order_totals_follow_what_is_typed() {
        showNewOrderForm()
        nameField(0).performTextInput("Floor Lamp")
        priceField(0).performTextInput("999.99")
        compose.onAllNodesWithContentDescription("Increase quantity")[0].performClick()
        compose.onAllNodesWithContentDescription("Increase quantity")[0].performClick()

        // Once on the line, once in the order total.
        compose.onAllNodesWithText("₱2,999.97").assertCountEquals(2)
        compose.onNodeWithText("1 product · 3 units").assertExists()
    }

    @Test
    fun saving_hands_over_every_line_in_entry_order() {
        showNewOrderForm()
        nameField(0).performTextInput("Velvet Sofa")
        priceField(0).performTextInput("12500.50")
        addLine()
        nameField(1).performTextInput("Throw Pillow")
        priceField(1).performScrollTo().performTextInput("450")

        save()

        val (order, items) = saved!!
        assertEquals(7L, order.buyerId)
        assertEquals(PaymentStatus.UNPAID, order.paymentStatus)
        assertEquals(listOf("Velvet Sofa", "Throw Pillow"), items.map { it.productName })
        assertEquals(listOf(Money.of("12500.50"), Money.of("450")), items.map { it.unitPrice })
        assertEquals(listOf(0, 1), items.map { it.position })
    }

    @Test
    fun an_incomplete_line_blocks_saving_and_says_why() {
        showNewOrderForm()
        save()

        assertNull(saved)
        compose.onNodeWithText("Product name is required", useUnmergedTree = true).assertExists()
    }

    // ---- Closing ---------------------------------------------------------------------------

    @Test
    fun an_untouched_form_closes_without_asking() {
        showNewOrderForm()
        cancel()

        assertTrue(dismissed)
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
    }

    @Test
    fun back_asks_before_throwing_away_what_was_typed() {
        showNewOrderForm()
        nameField(0).performTextInput("Velvet Sofa")

        Espresso.closeSoftKeyboard()
        Espresso.pressBack()

        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
        assertFalse(dismissed)

        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        compose.onNodeWithText("Velvet Sofa", substring = true).assertExists()
        assertFalse(dismissed)
    }

    @Test
    fun discard_closes_the_form() {
        showNewOrderForm()
        nameField(0).performTextInput("Velvet Sofa")

        cancel()
        compose.onNodeWithText("Discard").performClick()

        assertTrue(dismissed)
        assertNull(saved)
    }

    @Test
    fun an_unchanged_edit_closes_without_asking_but_a_changed_one_asks() {
        showEditForm()
        cancel()
        assertTrue(dismissed)

        dismissed = false
        compose.onAllNodesWithContentDescription("Increase quantity")[0].performClick()
        cancel()
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
        assertFalse(dismissed)
    }
}
