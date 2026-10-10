package com.example.myapplicationkoG

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    // Anti-spam cooldown: one request per 60s. Server rate limits are the
    // backstop; this stops casual hammering from burning the email quota.
    var cooldownLeft by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(cooldownLeft) {
        if (cooldownLeft > 0) {
            kotlinx.coroutines.delay(1000)
            cooldownLeft--
        }
    }

    PasswordPage(title = "Forgot password?") {
        Text(
            text = "Enter your email and we will send a verification link.",
            color = textColorForTheme(Color.DarkGray),
            fontSize = 14.sp
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { if (it.length <= 50) email = it },
            label = { Text("Email address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        RecoveryButton(
            text = when {
                isSending -> "Sending..."
                cooldownLeft > 0 -> "Resend in ${cooldownLeft}s"
                else -> "Send verification email"
            },
            enabled = !isSending && cooldownLeft == 0
        ) {
            val trimmed = email.trim()
            if (trimmed.isBlank()) {
                message = "Please enter your email address."
                isError = true
                return@RecoveryButton
            }
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(trimmed).matches() || trimmed.length > 254) {
                message = "Enter a valid email address."
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
                    // Neutral on purpose: identical message whether or not the
                    // address exists, so attackers can't enumerate accounts.
                    // Real failures go to logcat, never to the screen.
                    isError = false
                    message = "If this email is registered, a verification link is on its way. Check inbox and spam."
                } catch (e: Exception) {
                    e.printStackTrace()
                    isError = false
                    message = "If this email is registered, a verification link is on its way. Check inbox and spam."
                } finally {
                    isSending = false
                    cooldownLeft = 60
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
            onValueChange = { if (it.length <= 20) password = it },
            label = { Text("New password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = confirmPassword,
            onValueChange = { if (it.length <= 20) confirmPassword = it },
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

                        // Kill the recovery session: forces a fresh login with the
                        // new password (proves it works) instead of lingering logged
                        // in on a consumed one-time link.
                        runCatching { supabase.auth.signOut() }

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
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .imePadding()
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
    // Same primary-button style as the rest of the app (Edit profile, Log out):
    // Cyan fill, MidnightBlue text, 12dp corners, 52dp height.
    Button(
        onClick = onClick,
        enabled = enabled,
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
        Text(
            text = text,
            fontWeight = FontWeight.Bold,
            color = if (enabled) MidnightBlue else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 16.sp
        )
    }
}
