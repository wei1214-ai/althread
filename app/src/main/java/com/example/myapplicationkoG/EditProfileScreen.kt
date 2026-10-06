package com.example.myapplicationkoG

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplicationkoG.ui.ProfileRepository
import com.example.myapplicationkoG.ui.theme.Cyan
import com.example.myapplicationkoG.ui.theme.MidnightBlue
import kotlinx.coroutines.launch

@Composable
fun EditProfileScreen(
    onBack: () -> Unit
) {
    val repository = remember { ProfileRepository() }
    val scope = rememberCoroutineScope()

    var username by rememberSaveable { mutableStateOf("") }
    var bio by rememberSaveable { mutableStateOf("") }
    var originalUsername by rememberSaveable { mutableStateOf("") }
    var originalBio by rememberSaveable { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val profile = repository.getMyProfile()
            username = profile.username.orEmpty()
            bio = profile.bio.orEmpty()
            originalUsername = profile.username.orEmpty()
            originalBio = profile.bio.orEmpty()
        } catch (e: Exception) {
            errorMessage = e.message ?: "Could not load profile"
        }
    }

    val hasChanges = username.trim() != originalUsername.trim() || bio.trim() != originalBio.trim()
    val isSaveEnabled = !isSaving && hasChanges && username.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("Edit profile", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        }
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { if (!it.contains("\n")) username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = bio,
            onValueChange = { if (it.lines().size <= 5) bio = it },
            label = { Text("Bio") },
            modifier = Modifier.fillMaxWidth().height(120.dp),
            maxLines = 5
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            enabled = isSaveEnabled,
            onClick = {
                if (username.isBlank()) {
                    errorMessage = "Username cannot be empty"
                    return@Button
                }

                scope.launch {
                    try {
                        isSaving = true
                        repository.updateMyProfile(
                            username = username.trim(),
                            bio = bio.trim()
                        )
                        onBack()
                    } catch (e: Exception) {
                        errorMessage = e.message ?: "Could not save profile"
                    } finally {
                        isSaving = false
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Cyan,
                contentColor = MidnightBlue,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Text(if (isSaving) "Saving..." else "Save profile", fontWeight = FontWeight.Bold, color = if (isSaveEnabled) MidnightBlue else MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (errorMessage.isNotBlank()) {
            Text(errorMessage)
        }
    }
}