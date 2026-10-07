package com.blushdesk.app.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blushdesk.app.data.local.database.Buyer
import com.blushdesk.app.ui.buyer.AddBuyerDialog
import com.blushdesk.app.ui.components.PhotoActions
import com.blushdesk.app.ui.theme.ShowroomPinkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The buyer form: a contact number, a Facebook name, or both. */
@RunWith(AndroidJUnit4::class)
class BuyerFormDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private var saved: Buyer? = null

    @Before
    fun setUp() {
        compose.setContent {
            ShowroomPinkTheme {
                AddBuyerDialog(
                    photoActions = PhotoActions(importPhoto = { null }, discard = {}),
                    onSave = { saved = it },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Full name").performTextInput("Carla Mendoza")
    }

    private fun save() = compose.onNode(hasText("Add buyer") and hasClickAction()).performClick()

    @Test
    fun a_facebook_name_alone_is_enough() {
        compose.onNodeWithText("Facebook name").performTextInput("Carla Blush Home")
        save()

        assertEquals("Carla Blush Home", saved?.facebookName)
        assertEquals("", saved?.contactNumber)
    }

    @Test
    fun a_contact_number_alone_is_still_enough() {
        compose.onNodeWithText("Contact number").performTextInput("0920 222 3344")
        save()

        assertEquals("0920 222 3344", saved?.contactNumber)
        assertEquals("", saved?.facebookName)
    }

    @Test
    fun neither_is_refused_with_a_message() {
        save()

        assertNull(saved)
        compose.onNodeWithText("Add a contact number or a Facebook name", useUnmergedTree = true).assertExists()
    }
}
