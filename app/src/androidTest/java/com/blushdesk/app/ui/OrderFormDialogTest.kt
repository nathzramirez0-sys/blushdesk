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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blushdesk.app.data.local.database.Order
import com.blushdesk.app.data.local.database.OrderItem
import com.blushdesk.app.domain.model.PaymentStatus
import com.blushdesk.app.ui.order.AddOrderDialog
import com.blushdesk.app.ui.theme.ShowroomPinkTheme
import com.blushdesk.app.utils.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The order form on its own: product lines, live totals and what it hands over on save. */
@RunWith(AndroidJUnit4::class)
class OrderFormDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private var saved: Pair<Order, List<OrderItem>>? = null

    @Before
    fun setUp() {
        compose.setContent {
            ShowroomPinkTheme {
                AddOrderDialog(buyerId = 7, buyerName = "Ana Reyes", onSave = { order, items -> saved = order to items }, onDismiss = {})
            }
        }
    }

    private fun nameField(line: Int) = compose.onAllNodesWithText("Product name")[line]
    private fun priceField(line: Int) = compose.onAllNodesWithText("Unit price")[line]
    private fun addLine() = compose.onNodeWithText("Add another product", substring = true).performScrollTo().performClick()
    private fun save() = compose.onNode(hasText("Add order") and hasClickAction()).performClick()

    @Test
    fun a_new_line_is_scrolled_into_view_with_the_cursor_in_its_name() {
        repeat(3) { addLine() }

        compose.onNodeWithText("Product 4").assertIsDisplayed()
        nameField(3).assertIsDisplayed().assertIsFocused()
    }

    @Test
    fun line_and_order_totals_follow_what_is_typed() {
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
        save()

        assertNull(saved)
        compose.onNodeWithText("Product name is required", useUnmergedTree = true).assertExists()
    }
}
