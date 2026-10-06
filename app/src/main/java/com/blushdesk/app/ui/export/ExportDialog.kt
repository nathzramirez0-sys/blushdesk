package com.blushdesk.app.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.blushdesk.app.data.local.database.ShowroomSummary
import com.blushdesk.app.ui.components.InlineMessage
import com.blushdesk.app.ui.theme.Dimens
import com.blushdesk.app.utils.APP_NAME

/**
 * Says what the Excel export will contain before it runs, then builds the workbook and either
 * saves it in Downloads on this device or hands it to the share sheet. Shows progress meanwhile;
 * the screen closes this dialog when the workbook is ready.
 */
@Composable
fun ExportDialog(
    summary: ShowroomSummary,
    exporting: Boolean,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val nothingToExport = summary.buyerCount == 0
    // On a phone in landscape the body is taller than the dialog, so it scrolls; the progress line
    // and the warning sit at its end, so bring them into view when they appear.
    val scroll = rememberScrollState()
    LaunchedEffect(exporting, nothingToExport) {
        if (exporting || nothingToExport) {
            withFrameNanos { } // let the new line be laid out first
            scroll.animateScrollTo(scroll.maxValue)
        }
    }
    AlertDialog(
        onDismissRequest = { if (!exporting) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = !exporting, dismissOnBackPress = !exporting),
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        icon = { Icon(Icons.Filled.TableChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Export to Excel") },
        text = {
            Column(modifier = Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                Text(
                    "Creates an .xlsx workbook from everything on this tablet. Download saves it in " +
                        "Download/$APP_NAME on this device; Share sends it by email, to Drive or to another app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SheetLine("Items", "Each order's products, with totals")
                SheetLine("Buyers", plural(summary.buyerCount, "buyer"))
                SheetLine("Orders", plural(summary.orderCount, "order"))
                SheetLine("Operator", "Your profile and showroom")
                SheetLine("Summary", "Totals and counts by status")
                when {
                    nothingToExport -> InlineMessage("There is nothing to export yet. Add a buyer first.", isError = true)
                    exporting -> Row(
                        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(Dimens.iconLarge), strokeWidth = Dimens.progressStroke)
                        Text("Creating the workbook…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        // Two buttons, not one Row: the dialog lays its buttons out in a flow row, so on a narrow
        // phone they wrap onto a second line instead of being squeezed.
        confirmButton = {
            val enabled = !exporting && !nothingToExport
            OutlinedButton(onClick = onDownload, enabled = enabled) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                Text("  Download to device")
            }
            Button(onClick = onShare, enabled = enabled) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                Text("  Share")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !exporting) { Text("Cancel") }
        },
    )
}

@Composable
private fun SheetLine(sheet: String, contents: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            sheet,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.width(96.dp),
        )
        Text(contents, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun plural(count: Int, noun: String) = if (count == 1) "1 $noun" else "$count ${noun}s"
