package com.blushdesk.app.ui.buyer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.blushdesk.app.data.local.database.OrderTotals
import com.blushdesk.app.domain.model.BuyerDetail
import com.blushdesk.app.domain.model.PaymentStatus
import com.blushdesk.app.ui.components.EmptyState
import com.blushdesk.app.ui.components.StatCard
import com.blushdesk.app.ui.order.OrderActions
import com.blushdesk.app.ui.order.orderHistory
import com.blushdesk.app.ui.theme.Dimens
import com.blushdesk.app.ui.theme.ShowroomTheme
import com.blushdesk.app.utils.APP_NAME
import com.blushdesk.app.utils.Money

/** Everything the detail pane can ask the screen to do. */
class BuyerDetailActions(
    val onEditBuyer: () -> Unit,
    val onDeleteBuyer: () -> Unit,
    val onAddOrder: () -> Unit,
    val onAddBuyer: () -> Unit,
    val onSelectOrder: (Long) -> Unit,
    val order: OrderActions,
)

/**
 * The right pane: the selected buyer's profile, a dashboard of their totals, and their order
 * history. With nobody selected it explains what to do instead.
 */
@Composable
fun BuyerDetailScreen(
    detail: BuyerDetail?,
    hasBuyers: Boolean,
    expandedOrderId: Long?,
    receiptOrderId: Long?,
    compact: Boolean,
    actions: BuyerDetailActions,
    modifier: Modifier = Modifier,
) {
    if (detail == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (hasBuyers) {
                EmptyState(
                    icon = Icons.Filled.TouchApp,
                    title = "Select a buyer",
                    message = "Choose someone from the list to see their profile, orders and receipts.",
                    onTint = true,
                )
            } else {
                EmptyState(
                    icon = Icons.Filled.PersonAdd,
                    title = "Welcome to $APP_NAME",
                    message = "Add your first buyer to start recording orders, receipts and exports.",
                    onTint = true,
                    action = {
                        Button(onClick = actions.onAddBuyer) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                            Text("  Add buyer")
                        }
                    },
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(if (compact) Dimens.spaceL else Dimens.spaceXxl),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceL),
    ) {
        item(key = "header") {
            BuyerDetailHeader(detail.buyer, onEdit = actions.onEditBuyer, onDelete = actions.onDeleteBuyer)
        }
        item(key = "totals") { TotalsRow(detail.totals, compact) }
        item(key = "history-header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Order history (${detail.orders.size})",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                Button(onClick = actions.onAddOrder) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                    Text("  Add order")
                }
            }
        }
        if (detail.orders.isEmpty()) {
            item(key = "no-orders") {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.ReceiptLong,
                    title = "No orders yet",
                    message = "Record this buyer's first purchase to track payment and delivery.",
                    onTint = true,
                    action = { FilledTonalButton(onClick = actions.onAddOrder) { Text("Add the first order") } },
                )
            }
        } else {
            orderHistory(
                orders = detail.orders,
                expandedOrderId = expandedOrderId,
                receiptOrderId = receiptOrderId,
                onSelectOrder = actions.onSelectOrder,
                actions = actions.order,
            )
        }
    }
}

/** Orders, open orders, paid and outstanding amounts for the buyer. Two rows of two on narrow windows. */
@Composable
private fun TotalsRow(totals: OrderTotals, compact: Boolean) {
    val colors = ShowroomTheme.colors
    val dueAccent = if (totals.outstandingAmount.signum() > 0) {
        colors.tone(PaymentStatus.UNPAID).foreground
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val cards = listOf<@Composable (Modifier) -> Unit>(
        { StatCard("Orders", totals.orderCount.toString(), Icons.Filled.ShoppingBag, it) },
        { StatCard("Open orders", totals.openOrders.toString(), Icons.Filled.LocalShipping, it, accent = MaterialTheme.colorScheme.tertiary) },
        { StatCard("Paid", Money.format(totals.paidAmount), Icons.Filled.Paid, it, accent = colors.tone(PaymentStatus.PAID).foreground) },
        { StatCard("Outstanding", Money.format(totals.outstandingAmount), Icons.Filled.Payments, it, accent = dueAccent) },
    )
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
            cards.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM)) { pair.forEach { it(Modifier.weight(1f)) } }
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM)) { cards.forEach { it(Modifier.weight(1f)) } }
    }
}
