package com.blushdesk.app.ui.buyer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Facebook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
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
import com.blushdesk.app.data.local.database.Buyer
import com.blushdesk.app.ui.components.FormDialog
import com.blushdesk.app.ui.components.FormField
import com.blushdesk.app.ui.components.PhotoActions
import com.blushdesk.app.ui.components.PhotoPickerField
import com.blushdesk.app.utils.Formats
import com.blushdesk.app.utils.Validation
import kotlinx.coroutines.launch
import java.time.Instant

/** Records a new buyer. The date added is set automatically. */
@Composable
fun AddBuyerDialog(
    photoActions: PhotoActions,
    onSave: (Buyer) -> Unit,
    onDismiss: () -> Unit,
) = BuyerFormDialog(initial = null, photoActions = photoActions, onSave = onSave, onDismiss = onDismiss)

/**
 * The buyer form shared by [AddBuyerDialog] ([initial] = null) and [EditBuyerDialog]: name, a
 * contact number or a Facebook name (at least one), optional email and photo. The date added is
 * set once and never edited.
 */
@Composable
internal fun BuyerFormDialog(
    initial: Buyer?,
    photoActions: PhotoActions,
    onSave: (Buyer) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var fullName by rememberSaveable { mutableStateOf(initial?.fullName.orEmpty()) }
    var contact by rememberSaveable { mutableStateOf(initial?.contactNumber.orEmpty()) }
    var facebook by rememberSaveable { mutableStateOf(initial?.facebookName.orEmpty()) }
    var email by rememberSaveable { mutableStateOf(initial?.email.orEmpty()) }
    var imageUri by rememberSaveable { mutableStateOf(initial?.profileImageUri) }
    var stagedPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    var attempted by rememberSaveable { mutableStateOf(false) }

    val nameError = Validation.name(fullName, "Buyer name")
    val contactError = Validation.buyerContact(contact, facebook)
    val facebookError = Validation.facebookName(facebook)
    val emailError = Validation.email(email)
    val valid = listOf(nameError, contactError, facebookError, emailError).all { it == null }

    fun cancel() {
        photoActions.discard(stagedPhoto)
        onDismiss()
    }

    FormDialog(
        title = if (initial == null) "Add buyer" else "Edit buyer",
        confirmLabel = if (initial == null) "Add buyer" else "Save",
        onDismiss = ::cancel,
        onConfirm = {
            attempted = true
            if (valid) {
                stagedPhoto = null
                onSave(
                    initial?.copy(
                        fullName = fullName,
                        contactNumber = contact,
                        facebookName = facebook,
                        email = email,
                        profileImageUri = imageUri,
                    )
                        ?: Buyer(
                            fullName = fullName,
                            contactNumber = contact,
                            facebookName = facebook,
                            email = email,
                            dateAdded = Instant.now(),
                            profileImageUri = imageUri,
                        ),
                )
            }
        },
    ) {
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
            value = contact,
            onValueChange = { contact = it },
            label = "Contact number",
            leadingIcon = Icons.Filled.Phone,
            keyboardType = KeyboardType.Phone,
            error = if (attempted) contactError else null,
            maxLength = 24,
        )
        FormField(
            value = facebook,
            onValueChange = { facebook = it },
            label = "Facebook name",
            leadingIcon = Icons.Filled.Facebook,
            error = if (attempted) facebookError else null,
            maxLength = Validation.MAX_NAME,
        )
        Text(
            text = "A contact number or a Facebook name is enough. Add both if you have them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FormField(
            value = email,
            onValueChange = { email = it },
            label = "Email (optional)",
            leadingIcon = Icons.Filled.Email,
            keyboardType = KeyboardType.Email,
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Done,
            error = if (attempted) emailError else null,
        )
        Text(
            text = if (initial == null) {
                "The date added is recorded automatically."
            } else {
                "Added on ${Formats.date(initial.dateAdded)}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
