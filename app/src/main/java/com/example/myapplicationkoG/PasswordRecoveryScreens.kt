package com.example.myapplicationkoG

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplicationkoG.ui.theme.Cyan
import com.example.myapplicationkoG.ui.theme.MidnightBlue
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch

@Composable
fun ForgotPasswordScreen(onBack: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    PasswordPage(title = "Forgot password?") {
        Text(
            text = "Enter your email and we will send a verification link.",
            color = textColorForTheme(Color.DarkGray),
            fontSize = 14.sp
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        RecoveryButton(text = if (isSending) "Sending..." else "Send verification email", enabled = !isSending) {
            if (email.isBlank()) {
                message = "Please enter your email address."
                isError = true
                return@RecoveryButton
            }
            scope.launch {
                isSending = true
                try {
                    supabase.auth.resetPasswordForEmail(
                        email = email.trim(),
                        redirectUrl = "altthread://login/reset-password"
                        )
                    isError = false
                    message = "Verification email sent. Open its link to choose a new password."
                } catch (e: Exception) {
                    isError = true
                    message = e.message ?: "Could not send the verification email."
                } finally {
                    isSending = false
                }
            }
        }
        message?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, color = if (isError) Color.Red else Color(0xFF2E7D32), fontSize = 13.sp)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Back to login",
            color = textColorForTheme(MidnightBlue),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { onBack() }
        )
    }
}

@Composable
fun ResetPasswordScreen(onPasswordUpdated: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    PasswordPage(title = "Create a new password") {
        Text(
            "Your email has been verified. Choose a new password.",
            color = textColorForTheme(Color.DarkGray),
            fontSize = 14.sp
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("New password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it },
            label = { Text("Confirm new password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        RecoveryButton(if (isSaving) "Saving..." else "Change password", enabled = !isSaving) {
            when {
                password.length < 6 -> {
                    isError = true
                    message = "Your password must contain at least 6 characters."
                }
                password != confirmPassword -> {
                    isError = true
                    message = "The passwords do not match."
                }
                else -> scope.launch {
                    isSaving = true
                    try {
                        val newPassword = password

                        supabase.auth.updateUser {
                            this.password = newPassword
                        }

                        isError = false
                        message = "Password updated successfully."
                        onPasswordUpdated()

                    } catch (e: Exception) {
                        isError = true
                        message = e.message ?: "Could not update the password. Open the email link again."
                    } finally {
                        isSaving = false
                    }
                }
            }
        }
        message?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, color = if (isError) Color.Red else Color(0xFF2E7D32), fontSize = 13.sp)
        }
    }
}

@Composable
private fun PasswordPage(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            title,
            color = textColorForTheme(MidnightBlue),
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp
        )
        Spacer(Modifier.height(20.dp))
        content()
    }
}

@Composable
private fun RecoveryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        textAlign = TextAlign.Center,
        color = if (enabled) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant },
                RoundedCornerShape(24.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp),
        fontSize = 16.sp
    )
}
