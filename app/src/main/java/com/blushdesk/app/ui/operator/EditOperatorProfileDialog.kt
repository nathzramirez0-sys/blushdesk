package com.blushdesk.app.ui.operator

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.blushdesk.app.data.local.database.OperatorProfile
import com.blushdesk.app.ui.components.FormDialog
import com.blushdesk.app.ui.components.FormField
import com.blushdesk.app.ui.components.PhotoActions
import com.blushdesk.app.ui.components.PhotoPickerField
import com.blushdesk.app.utils.Validation
import kotlinx.coroutines.launch

/**
 * View and edit (or set up on first launch) the one active operator profile: name, showroom,
 * email, phone and photo (camera or Photo Picker). These appear on the profile card, on every PDF
 * receipt header and on the Excel "Operator" sheet.
 */
@Composable
fun EditOperatorProfileDialog(
    initial: OperatorProfile,
    photoActions: PhotoActions,
    onSave: (OperatorProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var fullName by rememberSaveable { mutableStateOf(initial.fullName) }
    var storeName by rememberSaveable { mutableStateOf(initial.storeName) }
    var email by rememberSaveable { mutableStateOf(initial.email) }
    var phone by rememberSaveable { mutableStateOf(initial.phoneNumber) }
    var imageUri by rememberSaveable { mutableStateOf(initial.profileImageUri) }

    // A photo imported during this dialog that has not been saved yet. It is deleted again if the
    // user replaces it, removes it or cancels, so abandoned pictures do not pile up on disk.
    var stagedPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    var attempted by rememberSaveable { mutableStateOf(false) }

    val nameError = Validation.name(fullName, "Your name")
    val storeError = Validation.name(storeName, "Showroom name")
    val emailError = Validation.email(email)
    val phoneError = Validation.phone(phone, required = false)
    val valid = listOf(nameError, storeError, emailError, phoneError).all { it == null }

    fun cancel() {
        photoActions.discard(stagedPhoto)
        onDismiss()
    }

    FormDialog(
        title = if (initial.isSetUp) "Edit your profile" else "Set up your profile",
        confirmLabel = "Save",
        onDismiss = ::cancel,
        onConfirm = {
            attempted = true
            if (valid) {
                stagedPhoto = null // now owned by the saved profile
                onSave(
                    initial.copy(
                        fullName = fullName,
                        storeName = storeName,
                        email = email,
                        phoneNumber = phone,
                        profileImageUri = imageUri,
                    ),
                )
            }
        },
    ) {
        if (!initial.isSetUp) {
            Text(
                "Your name and showroom appear on every receipt you issue.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PhotoPickerField(
            imageUri = imageUri,
            name = fullName,
            onPicked = { uri ->
                scope.launch {
                    val imported = photoActions.importPhoto(uri) ?: return@launch
                    photoActions.discard(stagedPhoto)
                    stagedPhoto = imported
                    imageUri = imported
                }
            },
            onRemove = {
                photoActions.discard(stagedPhoto)
                stagedPhoto = null
                imageUri = null
            },
        )
        FormField(
            value = fullName,
            onValueChange = { fullName = it },
            label = "Full name",
            leadingIcon = Icons.Filled.Person,
            error = if (attempted) nameError else null,
            maxLength = Validation.MAX_NAME,
        )
        FormField(
            value = storeName,
            onValueChange = { storeName = it },
            label = "Showroom / store name",
            leadingIcon = Icons.Filled.Store,
            error = if (attempted) storeError else null,
            maxLength = Validation.MAX_NAME,
        )
        FormField(
            value = email,
            onValueChange = { email = it },
            label = "Email",
            leadingIcon = Icons.Filled.Email,
            keyboardType = KeyboardType.Email,
            capitalization = KeyboardCapitalization.None,
            error = if (attempted) emailError else null,
        )
        FormField(
            value = phone,
            onValueChange = { phone = it },
            label = "Phone number",
            leadingIcon = Icons.Filled.Phone,
            keyboardType = KeyboardType.Phone,
            imeAction = ImeAction.Done,
            error = if (attempted) phoneError else null,
            maxLength = 24,
        )
    }
}
