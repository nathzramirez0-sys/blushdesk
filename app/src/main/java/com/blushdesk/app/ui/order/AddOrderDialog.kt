package com.blushdesk.app.ui.order

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.blushdesk.app.data.local.database.Order
import com.blushdesk.app.data.local.database.OrderItem
import com.blushdesk.app.data.local.database.OrderWithItems
import com.blushdesk.app.domain.model.FulfillmentStatus
import com.blushdesk.app.domain.model.PaymentMode
import com.blushdesk.app.domain.model.PaymentStatus
import com.blushdesk.app.ui.components.FormDialog
import com.blushdesk.app.ui.components.FormField
import com.blushdesk.app.ui.components.InlineMessage
import com.blushdesk.app.ui.components.SegmentedChoice
import com.blushdesk.app.ui.theme.Dimens
import com.blushdesk.app.ui.theme.ShowroomTheme
import com.blushdesk.app.utils.Formats
import com.blushdesk.app.utils.Money
import com.blushdesk.app.utils.Validation
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private val PRICE_INPUT = Regex("""^\d{0,10}(\.\d{0,2})?$""")

/** One product line as typed: still text, so half-typed values survive until they are valid. */
private data class ItemDraft(val product: String = "", val price: String = "", val quantity: String = "1") {
    val unitPrice: BigDecimal? get() = Money.parse(price)
    val qty: Int? get() = quantity.trim().toIntOrNull()
    val errors: Triple<String?, String?, String?>
        get() = Triple(Validation.product(product), Validation.unitPrice(price), Validation.quantity(quantity))
    val isValid: Boolean get() = errors.toList().all { it == null }

    /** unit price x quantity, or null while either is incomplete. */
    val lineTotal: BigDecimal? get() {
        val p = unitPrice ?: return null
        val q = qty ?: return null
        return if (isValid) Money.total(p, q) else null
    }

    fun toItem(position: Int) = OrderItem(position = position, productName = product, unitPrice = unitPrice!!, quantity = qty!!)

    companion object {
        fun of(item: OrderItem) = ItemDraft(item.productName, Money.toInputString(item.unitPrice), item.quantity.toString())
    }
}

/** Keeps the product lines across process death: three strings per line. */
private val ItemDraftsSaver = listSaver<SnapshotStateList<ItemDraft>, String>(
    save = { drafts -> drafts.flatMap { listOf(it.product, it.price, it.quantity) } },
    restore = { flat -> flat.chunked(3).map { ItemDraft(it[0], it[1], it[2]) }.toMutableStateList() },
)

/** Records a new purchase for the selected buyer. */
@Composable
fun AddOrderDialog(
    buyerId: Long,
    buyerName: String,
    onSave: (Order, List<OrderItem>) -> Unit,
    onDismiss: () -> Unit,
) = OrderFormDialog(buyerId = buyerId, buyerName = buyerName, initial = null, onSave = onSave, onDismiss = onDismiss)

/**
 * The order form shared by [AddOrderDialog] ([initial] = null) and [EditOrderDialog]: one or more
 * product lines, then date, time, payment and fulfillment. Every line total (unit price x
 * quantity) and the order total are calculated on every keystroke and never typed in, so the
 * operator can read the total back to the customer before saving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrderFormDialog(
    buyerId: Long,
    buyerName: String,
    initial: OrderWithItems?,
    onSave: (Order, List<OrderItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val details = initial?.order

    val drafts = rememberSaveable(saver = ItemDraftsSaver) {
        (initial?.items?.map(ItemDraft::of) ?: listOf(ItemDraft())).toMutableStateList()
    }
    var purchasedAtMillis by rememberSaveable { mutableLongStateOf((details?.purchaseDateTime ?: Instant.now()).toEpochMilli()) }
    var mode by rememberSaveable { mutableStateOf(details?.paymentMode ?: PaymentMode.CASH) }
    var payment by rememberSaveable { mutableStateOf(details?.paymentStatus ?: PaymentStatus.UNPAID) }
    var stage by rememberSaveable { mutableStateOf(details?.fulfillmentStatus ?: FulfillmentStatus.PROCESSING) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    // The line just added with "Add another product", until it has been scrolled to and focused.
    // Not saveable on purpose: rotating the tablet should not jump the form again.
    var newLine by remember { mutableStateOf<Int?>(null) }

    val local = Instant.ofEpochMilli(purchasedAtMillis).atZone(zone).toLocalDateTime()
    val allValid = drafts.all { it.isValid }
    val orderTotal = if (allValid) drafts.fold(Money.ZERO) { sum, d -> sum + d.lineTotal!! } else null

    FormDialog(
        title = if (initial == null) "New order for $buyerName" else "Edit order",
        confirmLabel = if (initial == null) "Add order" else "Save",
        onDismiss = onDismiss,
        onConfirm = {
            attempted = true
            if (allValid && drafts.isNotEmpty()) {
                val purchased = Instant.ofEpochMilli(purchasedAtMillis)
                val items = drafts.mapIndexed { index, draft -> draft.toItem(index) }
                val order = details?.copy(
                    purchaseDateTime = purchased,
                    paymentMode = mode,
                    paymentStatus = payment,
                    fulfillmentStatus = stage,
                ) ?: Order(
                    buyerId = buyerId,
                    purchaseDateTime = purchased,
                    paymentMode = mode,
                    paymentStatus = payment,
                    fulfillmentStatus = stage,
                )
                onSave(order, items)
            }
        },
    ) {
        SectionLabel("Products")
        drafts.forEachIndexed { index, draft ->
            ItemEditor(
                index = index,
                draft = draft,
                showErrors = attempted,
                canRemove = drafts.size > 1,
                isNew = index == newLine,
                onShown = { newLine = null },
                onChange = { drafts[index] = it },
                onRemove = { drafts.removeAt(index) },
            )
        }
        OutlinedButton(
            onClick = {
                drafts.add(ItemDraft())
                newLine = drafts.lastIndex
            },
            enabled = drafts.size < Validation.MAX_ITEMS,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
            Text(if (drafts.size < Validation.MAX_ITEMS) "  Add another product" else "  ${Validation.MAX_ITEMS} products is the limit")
        }

        TotalCard(total = orderTotal, lines = drafts.size, units = drafts.sumOf { it.qty ?: 0 })

        SectionLabel("Purchase date and time")
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
            OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                Text("  " + Formats.date(local))
            }
            OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                Text("  " + Formats.time(local))
            }
        }

        SectionLabel("Payment mode")
        SegmentedChoice(PaymentMode.entries, mode, { it.label }, { mode = it })

        SectionLabel("Payment status")
        SegmentedChoice(PaymentStatus.entries, payment, { it.label }, { payment = it })
        if (payment.isPaid) InlineMessage("Paid orders can have a PDF receipt generated.", isError = false)

        SectionLabel("Fulfillment status")
        SegmentedChoice(FulfillmentStatus.entries, stage, { it.label }, { stage = it })
    }

    if (showDatePicker) {
        // The Material date picker works in UTC midnights, so convert the local date both ways.
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { picked ->
                        val date = Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate()
                        purchasedAtMillis = date.atTime(local.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showTimePicker) {
        TimeChooserDialog(
            initial = local.toLocalTime(),
            is24Hour = DateFormat.is24HourFormat(LocalContext.current),
            onConfirm = { time ->
                purchasedAtMillis = local.toLocalDate().atTime(time).atZone(zone).toInstant().toEpochMilli()
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
}

/**
 * One product line: name, unit price, quantity stepper and its line total. A line that was just
 * added ([isNew]) scrolls itself into view and puts the cursor in its name, because the new card
 * otherwise appears below the fold and the button seems to do nothing.
 */
@Composable
private fun ItemEditor(
    index: Int,
    draft: ItemDraft,
    showErrors: Boolean,
    canRemove: Boolean,
    isNew: Boolean,
    onShown: () -> Unit,
    onChange: (ItemDraft) -> Unit,
    onRemove: () -> Unit,
) {
    val (productError, priceError, qtyError) = draft.errors
    val bringIntoView = remember { BringIntoViewRequester() }
    val nameFocus = remember { FocusRequester() }
    if (isNew) {
        LaunchedEffect(Unit) {
            withFrameNanos { } // the card has to be laid out before it can be scrolled to
            bringIntoView.bringIntoView()
            nameFocus.requestFocus()
            onShown()
        }
    }
    Surface(
        modifier = Modifier.bringIntoViewRequester(bringIntoView),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(Dimens.border, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(start = Dimens.spaceM, end = Dimens.spaceXs, top = Dimens.spaceXs, bottom = Dimens.spaceM),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceS),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Product ${index + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = draft.lineTotal?.let { Money.format(it) } ?: "—",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(end = if (canRemove) 0.dp else Dimens.spaceM),
                )
                if (canRemove) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove product ${index + 1}", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Column(modifier = Modifier.padding(end = Dimens.spaceS), verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                FormField(
                    value = draft.product,
                    onValueChange = { onChange(draft.copy(product = it)) },
                    label = "Product name",
                    modifier = Modifier.focusRequester(nameFocus),
                    leadingIcon = Icons.Filled.Inventory2,
                    error = if (showErrors) productError else null,
                    maxLength = Validation.MAX_PRODUCT,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM), verticalAlignment = Alignment.Top) {
                    FormField(
                        value = draft.price,
                        onValueChange = { if (PRICE_INPUT.matches(it)) onChange(draft.copy(price = it)) },
                        label = "Unit price",
                        modifier = Modifier.weight(1f),
                        prefix = Money.SYMBOL,
                        keyboardType = KeyboardType.Decimal,
                        error = if (showErrors) priceError else null,
                    )
                    QuantityField(
                        value = draft.quantity,
                        onValueChange = { text -> if (text.length <= 4 && text.all { it.isDigit() }) onChange(draft.copy(quantity = text)) },
                        error = if (showErrors) qtyError else null,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Number field flanked by - and + buttons, for quantities where tapping beats typing. */
@Composable
private fun QuantityField(
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    modifier: Modifier = Modifier,
) {
    val current = value.toIntOrNull() ?: 1
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text("Quantity") },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { message -> { Text(message) } },
        textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        leadingIcon = {
            IconButton(
                onClick = { onValueChange(maxOf(1, current - 1).toString()) },
                enabled = current > 1,
            ) { Icon(Icons.Filled.Remove, contentDescription = "Decrease quantity") }
        },
        trailingIcon = {
            IconButton(
                onClick = { onValueChange(minOf(Validation.MAX_QUANTITY, current + 1).toString()) },
                enabled = current < Validation.MAX_QUANTITY,
            ) { Icon(Icons.Filled.Add, contentDescription = "Increase quantity") }
        },
    )
}

/** The live order total. Announced politely by screen readers as it changes. */
@Composable
private fun TotalCard(total: BigDecimal?, lines: Int, units: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Dimens.spaceL, vertical = Dimens.spaceM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("ORDER TOTAL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    text = if (total != null) {
                        "${if (lines == 1) "1 product" else "$lines products"} · $units ${if (units == 1) "unit" else "units"}"
                    } else {
                        "Complete every product's price and quantity"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = ShowroomTheme.colors.mutedOnTint,
                )
            }
            Text(
                text = total?.let { Money.format(it) } ?: "—",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** Material's TimePicker needs a dialog around it; this one sizes itself to the picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeChooserDialog(
    initial: LocalTime,
    is24Hour: Boolean,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = is24Hour)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.width(IntrinsicSize.Min),
        ) {
            Column(modifier = Modifier.padding(Dimens.spaceXxl)) {
                Text(
                    "Select time",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = Dimens.spaceL),
                )
                TimePicker(state = state)
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("OK") }
                }
            }
        }
    }
}
