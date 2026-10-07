package com.blushdesk.app.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

/**
 * Says what the Excel export will contain before it runs, shows progress while the workbook is
 * built, and hands it to the share sheet when ready (the screen closes this dialog then).
 */
@Composable
fun ExportDialog(
    summary: ShowroomSummary,
    exporting: Boolean,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val nothingToExport = summary.buyerCount == 0
    AlertDialog(
        onDismissRequest = { if (!exporting) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = !exporting, dismissOnBackPress = !exporting),
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        icon = { Icon(Icons.Filled.TableChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Export to Excel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                Text(
                    "Creates an .xlsx workbook from everything on this tablet and opens the share sheet, so you " +
                        "can email it, save it to Drive or Files, or send it to another app.",
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
        confirmButton = {
            Button(onClick = onExport, enabled = !exporting && !nothingToExport) { Text("Export & share") }
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
