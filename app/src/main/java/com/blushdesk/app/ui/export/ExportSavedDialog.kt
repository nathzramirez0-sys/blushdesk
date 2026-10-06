package com.blushdesk.app.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownloadDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.blushdesk.app.ui.theme.Dimens

/**
 * Shown after the workbook is saved to Downloads: which file, which folder, and how to get at it
 * later. Open hands it to the installed spreadsheet app straight away.
 */
@Composable
fun ExportSavedDialog(
    displayName: String,
    savedTo: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        icon = { Icon(Icons.Filled.FileDownloadDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Excel file saved") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                Text(displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Saved in ${savedTo.substringBeforeLast('/')} on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Find it later in the Files app under Downloads, or copy it to a computer with a USB cable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = onOpen) { Text("Open") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
