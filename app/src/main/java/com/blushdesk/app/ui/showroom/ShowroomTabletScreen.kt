package com.blushdesk.app.ui.showroom

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blushdesk.app.domain.model.PaymentStatus
import com.blushdesk.app.ui.buyer.AddBuyerDialog
import com.blushdesk.app.ui.buyer.BuyerDetailActions
import com.blushdesk.app.ui.buyer.BuyerDetailScreen
import com.blushdesk.app.ui.buyer.EditBuyerDialog
import com.blushdesk.app.ui.components.DeleteConfirmationDialog
import com.blushdesk.app.ui.components.LoadingIndicator
import com.blushdesk.app.ui.components.PhotoActions
import com.blushdesk.app.ui.components.ShowroomSnackbarHost
import com.blushdesk.app.ui.components.show
import com.blushdesk.app.ui.export.ExportDialog
import com.blushdesk.app.ui.export.ExportSavedDialog
import com.blushdesk.app.ui.operator.EditOperatorProfileDialog
import com.blushdesk.app.ui.order.AddOrderDialog
import com.blushdesk.app.ui.order.EditOrderDialog
import com.blushdesk.app.ui.order.OrderActions
import com.blushdesk.app.ui.order.ReceiptReadyDialog
import com.blushdesk.app.ui.theme.Dimens
import com.blushdesk.app.utils.APP_NAME
import com.blushdesk.app.utils.AppFiles
import com.blushdesk.app.utils.ExportDocument
import com.blushdesk.app.utils.Formats
import com.blushdesk.app.utils.ReceiptDocument
import com.blushdesk.app.utils.Sharing
import kotlinx.coroutines.launch

/**
 * Which modal, if any, is open. Only one at a time, so a single value is enough.
 *
 * Records are referred to by id and looked up in the current state when the dialog is drawn. That
 * keeps the value small enough to save with the screen ([ActiveDialogSaver]), so an open form
 * survives Android recreating the activity (a display-size change) or closing the app in the
 * background. What was typed comes back too, because every form keeps its fields with
 * rememberSaveable.
 */
private sealed interface ActiveDialog {
    data object None : ActiveDialog
    data object EditProfile : ActiveDialog
    data object AddBuyer : ActiveDialog
    data class EditBuyer(val buyerId: Long) : ActiveDialog
    data class AddOrder(val buyerId: Long) : ActiveDialog
    data class EditOrder(val orderId: Long) : ActiveDialog
    data class DeleteBuyer(val buyerId: Long) : ActiveDialog
    data class DeleteOrder(val orderId: Long) : ActiveDialog
    data object Export : ActiveDialog

    /** Not saved: the receipt is already in Downloads, so losing this dialog loses nothing. */
    data class ReceiptReady(val receipt: ReceiptDocument) : ActiveDialog

    /** Not saved either, for the same reason: the workbook is already in Downloads. */
    data class ExportSaved(val export: ExportDocument, val savedTo: String) : ActiveDialog
}

/** Saves an [ActiveDialog] as a kind and, where it has one, a record id: both fit in a Bundle. */
private val ActiveDialogSaver = listSaver<ActiveDialog, Any>(
    save = { dialog ->
        when (dialog) {
            ActiveDialog.None, is ActiveDialog.ReceiptReady, is ActiveDialog.ExportSaved -> emptyList()
            ActiveDialog.EditProfile -> listOf("profile")
            ActiveDialog.AddBuyer -> listOf("addBuyer")
            ActiveDialog.Export -> listOf("export")
            is ActiveDialog.EditBuyer -> listOf("editBuyer", dialog.buyerId)
            is ActiveDialog.AddOrder -> listOf("addOrder", dialog.buyerId)
            is ActiveDialog.EditOrder -> listOf("editOrder", dialog.orderId)
            is ActiveDialog.DeleteBuyer -> listOf("deleteBuyer", dialog.buyerId)
            is ActiveDialog.DeleteOrder -> listOf("deleteOrder", dialog.orderId)
        }
    },
    restore = { saved ->
        val id = saved.getOrNull(1) as? Long
        when (saved.firstOrNull()) {
            "profile" -> ActiveDialog.EditProfile
            "addBuyer" -> ActiveDialog.AddBuyer
            "export" -> ActiveDialog.Export
            "editBuyer" -> id?.let(ActiveDialog::EditBuyer)
            "addOrder" -> id?.let(ActiveDialog::AddOrder)
            "editOrder" -> id?.let(ActiveDialog::EditOrder)
            "deleteBuyer" -> id?.let(ActiveDialog::DeleteBuyer)
            "deleteOrder" -> id?.let(ActiveDialog::DeleteOrder)
            else -> null
        } ?: ActiveDialog.None
    },
)

/**
 * The app's one screen: an adaptive master-detail layout. At 600dp and wider both panes show side
 * by side; narrower windows (phone, split screen, Android 16+ ignoring the landscape request) show
 * one pane at a time with a back arrow.
 */
@Composable
fun ShowroomTabletScreen(viewModel: ShowroomViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var dialog by rememberSaveable(stateSaver = ActiveDialogSaver) { mutableStateOf<ActiveDialog>(ActiveDialog.None) }
    var showDetailWhenCompact by rememberSaveable { mutableStateOf(false) }
    var askedForProfile by rememberSaveable { mutableStateOf(false) }

    val photoActions = remember(viewModel) {
        PhotoActions(importPhoto = viewModel::importPhoto, discard = viewModel::discardPhoto)
    }

    fun share(file: java.io.File, mime: String, subject: String, title: String, noAppMessage: String) {
        if (!Sharing.share(context, file, mime, subject, title)) viewModel.reportError(noAppMessage)
    }

    // One-shot events. Each snackbar runs in its own coroutine so a message still on screen never
    // delays the receipt dialog or share sheet that follows it.
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.Success -> scope.launch { snackbar.show(event.message, isError = false) }
                is UiEvent.Error -> scope.launch { snackbar.show(event.message, isError = true) }
                is UiEvent.ReceiptReady -> dialog = ActiveDialog.ReceiptReady(event.receipt)
                is UiEvent.ShareWorkbook -> {
                    if (dialog == ActiveDialog.Export) dialog = ActiveDialog.None
                    share(
                        file = event.export.file,
                        mime = AppFiles.MIME_XLSX,
                        subject = "$APP_NAME export ${event.export.displayName}",
                        title = "Share Excel export",
                        noAppMessage = "No app on this tablet can receive the Excel file.",
                    )
                }
                is UiEvent.WorkbookSaved -> dialog = ActiveDialog.ExportSaved(event.export, event.savedTo)
            }
        }
    }

    // A dialog about a record that is no longer there closes itself. Nothing is decided while the
    // buyer is still loading (after a restore), so a reopened form waits for its record instead.
    LaunchedEffect(dialog, state.detail) {
        val detail = state.detail ?: return@LaunchedEffect
        val gone = when (val open = dialog) {
            is ActiveDialog.EditBuyer -> detail.buyer.id != open.buyerId
            is ActiveDialog.AddOrder -> detail.buyer.id != open.buyerId
            is ActiveDialog.DeleteBuyer -> detail.buyer.id != open.buyerId
            is ActiveDialog.EditOrder -> detail.orders.none { it.order.id == open.orderId }
            is ActiveDialog.DeleteOrder -> detail.orders.none { it.order.id == open.orderId }
            else -> false
        }
        if (gone) dialog = ActiveDialog.None
    }

    // First launch: ask for the operator's details once, since every receipt needs them.
    LaunchedEffect(state.operatorLoaded, state.operator.isSetUp) {
        if (state.operatorLoaded && !state.operator.isSetUp && !askedForProfile) {
            askedForProfile = true
            dialog = ActiveDialog.EditProfile
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compact = maxWidth < Dimens.dualPaneMinWidth
        val masterWidth = when {
            maxWidth >= Dimens.masterPaneWideFrom -> Dimens.masterPaneWide
            maxWidth >= Dimens.masterPaneMediumFrom -> Dimens.masterPaneMedium
            else -> Dimens.masterPaneCompact
        }
        val detailOnly = compact && showDetailWhenCompact
        BackHandler(enabled = detailOnly) { showDetailWhenCompact = false }

        val detailActions = BuyerDetailActions(
            onEditBuyer = { state.detail?.buyer?.let { dialog = ActiveDialog.EditBuyer(it.id) } },
            onDeleteBuyer = { state.detail?.buyer?.let { dialog = ActiveDialog.DeleteBuyer(it.id) } },
            onAddOrder = { state.detail?.buyer?.let { dialog = ActiveDialog.AddOrder(it.id) } },
            onAddBuyer = { dialog = ActiveDialog.AddBuyer },
            onSelectOrder = viewModel::selectOrder,
            order = OrderActions(
                onEdit = { dialog = ActiveDialog.EditOrder(it.order.id) },
                onDelete = { dialog = ActiveDialog.DeleteOrder(it.order.id) },
                onAdvance = { viewModel.advanceOrder(it.order) },
                onSetFulfillment = { order, status -> viewModel.setFulfillmentStatus(order.order.id, status) },
                onMarkPaid = { viewModel.setPaymentStatus(it.order.id, PaymentStatus.PAID) },
                onReceipt = { viewModel.generateReceipt(it.order.id) },
            ),
        )

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { ShowroomSnackbarHost(snackbar) },
            topBar = {
                ShowroomTopBar(
                    subtitle = state.operator.storeName.ifBlank { "Showroom manager" },
                    showBack = detailOnly,
                    onBack = { showDetailWhenCompact = false },
                    compact = compact,
                    onExport = { dialog = ActiveDialog.Export },
                )
            },
        ) { padding ->
            val master: @Composable (Modifier) -> Unit = { modifier ->
                MasterPane(
                    state = state,
                    onQueryChange = viewModel::onQueryChange,
                    onSelectBuyer = {
                        viewModel.selectBuyer(it)
                        showDetailWhenCompact = true
                    },
                    onEditProfile = { dialog = ActiveDialog.EditProfile },
                    onAddBuyer = { dialog = ActiveDialog.AddBuyer },
                    onAddOrder = {
                        state.detail?.buyer?.let {
                            dialog = ActiveDialog.AddOrder(it.id)
                            showDetailWhenCompact = true
                        }
                    },
                    pinTools = maxHeight >= Dimens.pinToolsMinHeight,
                    modifier = modifier,
                )
            }
            val detail: @Composable (Modifier) -> Unit = { modifier ->
                if (state.isLoading) {
                    Box(modifier, contentAlignment = Alignment.Center) { LoadingIndicator("Opening your showroom…") }
                } else {
                    BuyerDetailScreen(
                        detail = state.detail,
                        hasBuyers = state.summary.buyerCount > 0,
                        expandedOrderId = state.expandedOrderId,
                        receiptOrderId = state.receiptOrderId,
                        compact = compact,
                        actions = detailActions,
                        modifier = modifier,
                    )
                }
            }

            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                when {
                    detailOnly -> detail(Modifier.fillMaxSize())
                    compact -> master(Modifier.fillMaxSize())
                    else -> Row(modifier = Modifier.fillMaxSize()) {
                        master(Modifier.width(masterWidth).fillMaxHeight())
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        detail(Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }

    val dismiss = { dialog = ActiveDialog.None }
    fun buyerOf(id: Long) = state.detail?.buyer?.takeIf { it.id == id }
    fun orderOf(id: Long) = state.detail?.orders?.firstOrNull { it.order.id == id }
    when (val active = dialog) {
        ActiveDialog.None -> Unit

        ActiveDialog.EditProfile -> EditOperatorProfileDialog(
            initial = state.operator,
            photoActions = photoActions,
            onSave = {
                viewModel.saveOperator(it)
                dismiss()
            },
            onDismiss = dismiss,
        )

        ActiveDialog.AddBuyer -> AddBuyerDialog(
            photoActions = photoActions,
            onSave = {
                viewModel.saveBuyer(it)
                showDetailWhenCompact = true
                dismiss()
            },
            onDismiss = dismiss,
        )

        is ActiveDialog.EditBuyer -> buyerOf(active.buyerId)?.let { buyer ->
            EditBuyerDialog(
                buyer = buyer,
                photoActions = photoActions,
                onSave = {
                    viewModel.saveBuyer(it)
                    dismiss()
                },
                onDismiss = dismiss,
            )
        }

        is ActiveDialog.AddOrder -> buyerOf(active.buyerId)?.let { buyer ->
            AddOrderDialog(
                buyerId = buyer.id,
                buyerName = buyer.fullName,
                onSave = { order, items ->
                    viewModel.saveOrder(order, items)
                    dismiss()
                },
                onDismiss = dismiss,
            )
        }

        is ActiveDialog.EditOrder -> orderOf(active.orderId)?.let { order ->
            EditOrderDialog(
                order = order,
                buyerName = state.detail?.buyer?.fullName.orEmpty(),
                onSave = { edited, items ->
                    viewModel.saveOrder(edited, items)
                    dismiss()
                },
                onDismiss = dismiss,
            )
        }

        is ActiveDialog.DeleteBuyer -> state.detail?.takeIf { it.buyer.id == active.buyerId }?.let { detail ->
            val orders = detail.orders.size
            DeleteConfirmationDialog(
                title = "Delete ${detail.buyer.fullName}?",
                message = if (orders == 0) {
                    "This buyer will be removed. This cannot be undone."
                } else {
                    "Their $orders order${if (orders == 1) "" else "s"} will be deleted too. This cannot be undone."
                },
                onConfirm = {
                    viewModel.deleteBuyer(detail.buyer.id)
                    showDetailWhenCompact = false
                    dismiss()
                },
                onDismiss = dismiss,
            )
        }

        is ActiveDialog.DeleteOrder -> orderOf(active.orderId)?.let { order ->
            DeleteConfirmationDialog(
                title = "Delete this order?",
                message = "${Formats.itemsSummary(order.items.map { it.productName })} " +
                    "(${Formats.orderNumber(order.order.id)}) will be removed. This cannot be undone.",
                onConfirm = {
                    viewModel.deleteOrder(order.order.id)
                    dismiss()
                },
                onDismiss = dismiss,
            )
        }

        ActiveDialog.Export -> ExportDialog(
            summary = state.summary,
            exporting = state.exporting,
            onDownload = viewModel::downloadExcel,
            onShare = viewModel::shareExcel,
            onDismiss = dismiss,
        )

        is ActiveDialog.ExportSaved -> ExportSavedDialog(
            displayName = active.export.displayName,
            savedTo = active.savedTo,
            onOpen = {
                if (Sharing.view(context, active.export.file, AppFiles.MIME_XLSX)) {
                    dismiss()
                } else {
                    viewModel.reportError("No app on this tablet can open Excel files. Install Excel or Google Sheets, or use Share.")
                }
            },
            onDismiss = dismiss,
        )

        is ActiveDialog.ReceiptReady -> ReceiptReadyDialog(
            receipt = active.receipt,
            onOpen = {
                if (!Sharing.view(context, active.receipt.file, AppFiles.MIME_PDF)) {
                    viewModel.reportError("No PDF viewer is installed. Use Share instead.")
                }
            },
            onShare = {
                share(
                    file = active.receipt.file,
                    mime = AppFiles.MIME_PDF,
                    subject = "Receipt ${active.receipt.displayName}",
                    title = "Share receipt",
                    noAppMessage = "No app on this tablet can receive the receipt.",
                )
            },
            onDismiss = dismiss,
        )
    }
}

/** App bar with the brand, the store name, and the prominent "Export to Excel" action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShowroomTopBar(
    subtitle: String,
    showBack: Boolean,
    onBack: () -> Unit,
    compact: Boolean,
    onExport: () -> Unit,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        navigationIcon = {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to buyers")
                }
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(Dimens.brandMark)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.ShoppingBag,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(Dimens.iconLarge),
                    )
                }
                Column(modifier = Modifier.padding(start = Dimens.spaceM)) {
                    Text(APP_NAME, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary)
                    if (!compact) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        actions = {
            Button(onClick = onExport, modifier = Modifier.padding(end = Dimens.spaceL)) {
                Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                Text(if (compact) "  Export" else "  Export to Excel")
            }
        },
    )
}
