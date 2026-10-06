package com.blushdesk.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blushdesk.app.data.local.database.ShowroomSummary
import com.blushdesk.app.ui.export.ExportDialog
import com.blushdesk.app.ui.export.ExportSavedDialog
import com.blushdesk.app.ui.showroom.ShowroomUiState
import com.blushdesk.app.ui.theme.ShowroomPinkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Export to Excel offers both ways out: save on this device, or share. */
@RunWith(AndroidJUnit4::class)
class ExportDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val twoBuyers: ShowroomSummary = ShowroomUiState.EMPTY_SUMMARY.copy(buyerCount = 2, orderCount = 3)

    /** The button, not the description that mentions it. */
    private fun button(label: String) = compose.onNode(hasText(label, substring = true) and hasClickAction())

    private fun showExport(summary: ShowroomSummary = twoBuyers, exporting: Boolean = false) = compose.setContent {
        ShowroomPinkTheme {
            ExportDialog(
                summary = summary,
                exporting = exporting,
                onDownload = { calls += "download" },
                onShare = { calls += "share" },
                onDismiss = { calls += "dismiss" },
            )
        }
    }

    @Test
    fun download_and_share_are_separate_choices() {
        showExport()

        button("Download to device").performClick()
        button("Share").performClick()

        assertEquals(listOf("download", "share"), calls)
    }

    @Test
    fun the_dialog_says_where_a_download_goes() {
        showExport()
        compose.onNodeWithText("Download/FergBentables", substring = true).assertIsDisplayed()
    }

    @Test
    fun neither_choice_is_offered_with_nothing_to_export() {
        showExport(summary = ShowroomUiState.EMPTY_SUMMARY)

        button("Download to device").assertIsNotEnabled()
        button("Share").assertIsNotEnabled()
        compose.onNodeWithText("There is nothing to export yet", substring = true).assertIsDisplayed()
    }

    @Test
    fun both_choices_wait_while_the_workbook_is_being_made() {
        var exporting by mutableStateOf(true)
        compose.setContent {
            ShowroomPinkTheme {
                ExportDialog(twoBuyers, exporting, onDownload = { calls += "download" }, onShare = { calls += "share" }, onDismiss = {})
            }
        }

        compose.onNodeWithText("Creating the workbook", substring = true).assertIsDisplayed()
        button("Download to device").assertIsNotEnabled()
        button("Share").assertIsNotEnabled()

        exporting = false
        button("Download to device").assertIsEnabled()
        button("Share").assertIsEnabled()
    }

    @Test
    fun the_saved_dialog_names_the_file_and_folder_and_can_open_it() {
        compose.setContent {
            ShowroomPinkTheme {
                ExportSavedDialog(
                    displayName = "FergBentables_Export_2026-10-06_1430.xlsx",
                    savedTo = "Download/FergBentables/FergBentables_Export_2026-10-06_1430.xlsx",
                    onOpen = { calls += "open" },
                    onDismiss = { calls += "done" },
                )
            }
        }

        compose.onNodeWithText("FergBentables_Export_2026-10-06_1430.xlsx").assertIsDisplayed()
        compose.onNodeWithText("Saved in Download/FergBentables on this device.").assertIsDisplayed()
        compose.onNodeWithText("Open").performClick()
        compose.onNodeWithText("Done").performClick()

        assertEquals(listOf("open", "done"), calls)
    }
}
