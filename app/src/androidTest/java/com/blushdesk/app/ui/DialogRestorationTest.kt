package com.blushdesk.app.ui

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blushdesk.app.TestData
import com.blushdesk.app.data.local.database.AppDatabase
import com.blushdesk.app.data.repository.OfflineShowroomRepository
import com.blushdesk.app.ui.showroom.ShowroomTabletScreen
import com.blushdesk.app.ui.showroom.ShowroomViewModel
import com.blushdesk.app.ui.theme.ShowroomPinkTheme
import com.blushdesk.app.utils.DocumentService
import com.blushdesk.app.utils.ExportDocument
import com.blushdesk.app.utils.PhotoStore
import com.blushdesk.app.utils.ReceiptDocument
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole screen over a real (in-memory) database. [StateRestorationTester] saves the screen's
 * state, throws the composition away and builds it again from what was saved: what happens when
 * Android recreates the activity, for example after a display-size change.
 */
@RunWith(AndroidJUnit4::class)
class DialogRestorationTest {

    @get:Rule
    val compose = createComposeRule()

    private val restoration = StateRestorationTester(compose)
    private lateinit var db: AppDatabase
    private lateinit var repo: OfflineShowroomRepository
    private var orderId = 0L

    private val noPhotos = object : PhotoStore {
        override suspend fun importPhoto(source: Uri): String = error("unused")
        override fun delete(imageUri: String?) = Unit
    }
    private val noDocuments = object : DocumentService {
        override suspend fun createReceipt(orderId: Long): ReceiptDocument = error("unused")
        override suspend fun createWorkbook(): ExportDocument = error("unused")
        override suspend fun saveToDownloads(export: ExportDocument): String = error("unused")
    }

    @Before
    fun setUp() {
        db = TestData.inMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        repo = OfflineShowroomRepository(db, noPhotos)
        runBlocking {
            repo.saveOperator(TestData.operator)
            val ana = repo.saveBuyer(TestData.buyer("Ana Reyes"))
            orderId = repo.saveOrder(TestData.order(ana), listOf(TestData.item("Velvet Sofa", "12500.50", 2)))
        }
        val viewModel = ShowroomViewModel(repo, noPhotos, noDocuments)
        restoration.setContent { ShowroomPinkTheme { ShowroomTabletScreen(viewModel) } }
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("Velvet Sofa").fetchSemanticsNodes().isNotEmpty() }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun an_open_order_form_keeps_what_was_typed() {
        compose.onAllNodes(hasText("Add order", substring = true) and hasClickAction()).onFirst().performClick()
        compose.onNodeWithText("Product name").performTextInput("Rattan Chair")
        compose.onNodeWithText("Unit price").performTextInput("4200")

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("New order for Ana Reyes").assertIsDisplayed()
        compose.onNodeWithText("Rattan Chair", substring = true).assertExists()
        compose.onAllNodesWithText("₱4,200.00").onFirst().assertExists()
    }

    @Test
    fun an_order_being_edited_stays_open_with_its_changes() {
        compose.onNodeWithContentDescription("Edit order", substring = true).performClick()
        // The order card behind the dialog says "Velvet Sofa" too, so aim at the text field.
        compose.onNode(hasSetTextAction() and hasText("Velvet Sofa", substring = true)).performTextReplacement("Velvet Sofa, Blush")

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Edit order").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Velvet Sofa, Blush", substring = true)).assertExists()
    }

    @Test
    fun a_form_for_an_order_that_is_deleted_closes_itself() {
        compose.onNodeWithContentDescription("Edit order", substring = true).performClick()
        compose.onNodeWithText("Edit order").assertIsDisplayed()

        runBlocking { repo.deleteOrder(orderId) }

        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("Edit order").fetchSemanticsNodes().isEmpty() }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
