package com.example.myapplicationkoG

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import android.util.Patterns
import androidx.compose.material3.MaterialTheme
import com.example.myapplicationkoG.ui.theme.Cyan
import com.example.myapplicationkoG.ui.theme.LightGray
import com.example.myapplicationkoG.ui.theme.MidnightBlue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.launch

private fun String.isValidGmail(): Boolean {
    val t = trim()
    if (t.isBlank() || t.length > 254) return false
    if (!Patterns.EMAIL_ADDRESS.matcher(t).matches()) return false
    val lower = t.lowercase()
    if (!lower.endsWith("@gmail.com")) return false
    val local = lower.substringBefore("@")
    if (local.length < 6 || local.length > 30) return false
    if (local.startsWith(".") || local.endsWith(".") || local.contains("..")) return false
    if (!local.matches(Regex("^[a-z0-9._]+$"))) return false
    return true
}


@Composable
private fun AuthTabItem(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape = RoundedCornerShape(size = 25.dp))
            .background(color = if (isSelected) Cyan else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MidnightBlue else Color.Gray
        )
    }
}

@Composable
private fun AuthInputField(
    placeholder: String,
    value: String,
    onValueChange: (String)-> Unit,
    isPassword: Boolean = false,
    errorMessage: String? = null,
    maxLength: Int? = null,
    modifier: Modifier = Modifier
) {
    var passwordVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {Text(placeholder, fontSize = 14.sp)},
        singleLine = true,
        isError = errorMessage != null,
        supportingText = null,
        trailingIcon = if (isPassword) {
            {
                androidx.compose.material3.IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    androidx.compose.material3.Icon(
                        painter = painterResource(id = if (passwordVisible) R.drawable.visibilityon else R.drawable.visibilityoff),
                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        tint = Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        } else null,
        colors = OutlinedTextFieldDefaults.colors(
            errorBorderColor = Color.Red,
            errorPlaceholderColor = Color.Red
        ),
        visualTransformation =
            if (isPassword && !passwordVisible) PasswordVisualTransformation()
            else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPassword) KeyboardType.Password
            else KeyboardType.Email
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
    )
}

@Composable
private fun AuthPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(55.dp)
            .clip(shape = RoundedCornerShape(size = 25.dp))
            .background(color = Cyan)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, fontWeight = FontWeight.Bold, color = MidnightBlue)
    }
}

@Composable
fun AuthScreen(
    onLoginSuccess: () -> Unit = {},
    onForgotPassword: () -> Unit = {}
) {
    var isLogin by rememberSaveable { mutableStateOf(true) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var emailError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var confirmPasswordError by remember { mutableStateOf<String?>(null) }
    var bannerMessage by remember { mutableStateOf<String?>(null) }
    var isBannerSuccess by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(isImeVisible) {
        if (isImeVisible) {
            kotlinx.coroutines.delay(150)
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    fun clearFieldErrors() {
        emailError = null
        passwordError = null
        confirmPasswordError = null
    }

    fun validateLogin(): Boolean{
        emailError = when {
            email.isBlank() -> "Email is required"
            !email.trim().isValidGmail() -> "Enter a valid Gmail address (e.g. name@gmail.com)."
            else -> null
        }
        if (emailError != null) {
            passwordError = null
            bannerMessage = emailError
            isBannerSuccess = false
            return false
        }
        passwordError = if (password.isBlank()) "Password is required" else null
        bannerMessage = passwordError
        isBannerSuccess = false
        return passwordError == null
    }

    fun validateRegister(): Boolean{
        emailError = when{
            email.isBlank()->"Email is required."
            !email.trim().isValidGmail()-> "Enter a valid Gmail address (e.g. name@gmail.com)."
            else -> null
        }
        if (emailError != null) {
            passwordError = null
            confirmPasswordError = null
            bannerMessage = emailError
            isBannerSuccess = false
            return false
        }
        passwordError = when{
            password.isBlank()-> "Password is required."
            password.length<6 -> "Password must be at least 6 characters."
            else -> null
        }
        if (passwordError != null) {
            confirmPasswordError = null
            bannerMessage = passwordError
            isBannerSuccess = false
            return false
        }
        confirmPasswordError = when{
            confirmPassword.isBlank()->"Please confirm your password."
            password != confirmPassword->"Password does not match."
            else -> null
        }
        bannerMessage = confirmPasswordError
        isBannerSuccess = false
        return confirmPasswordError == null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .imePadding()
            .navigationBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.banner),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Transparent,
                                MidnightBlue.copy(alpha = 1.0f)
                            )
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(all = 24.dp)
            ) {
                Text(
                    text = "AltThread",
                    fontSize = 45.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Cyan
                )
                Text(
                    text = "Upcycle. Design. Inspire.",
                    fontSize = 25.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = White
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(shape = RoundedCornerShape(size = 25.dp))
                        .background(color = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    AuthTabItem(
                        text = "Log In",
                        isSelected = isLogin,
                        onClick = {
                            isLogin = true; bannerMessage = null; clearFieldErrors()
                            email = ""; password = ""; confirmPassword = ""
                        },
                        modifier = Modifier.weight(1f)
                    )
                    AuthTabItem(
                        text = "Register",
                        isSelected = !isLogin,
                        onClick = {
                            isLogin = false; bannerMessage = null; clearFieldErrors()
                            email = ""; password = ""; confirmPassword = ""
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (isLogin) {
                    AuthInputField(
                        placeholder = "Email Address",
                        value = email,
                        onValueChange = { if (it.length <= 254) { email = it; emailError = null } },
                        errorMessage = emailError,
                        maxLength = 254
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    AuthInputField(placeholder = "Password",
                        value = password,
                        onValueChange = { if (it.length <= 64) { password=it; passwordError = null } },
                        isPassword = true,
                        errorMessage = passwordError,
                        maxLength = 64
                    )
                    Spacer(modifier = Modifier.height(20.dp))

                    AnimatedVisibility(visible = bannerMessage != null) {
                        Column {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isBannerSuccess) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer)
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = bannerMessage ?: "",
                                    color = if (isBannerSuccess) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }

                    AuthPrimaryButton(text = "Log In",
                        onClick = {
                            if(validateLogin()) {
                                val enteredEmail = email
                                val enteredPassword = password
                                scope.launch {
                                    try {
                                        supabase.auth.signInWith(Email){
                                            this.email= enteredEmail.trim()
                                            this.password= enteredPassword
                                        }
                                        onLoginSuccess()
                                    }catch (exception: Exception){
                                        bannerMessage = exception.message?:"Incorrect email or password."
                                        isBannerSuccess = false
                                    }
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onForgotPassword() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Forgot password?",
                            color = textColorForTheme(MidnightBlue),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    AuthInputField(placeholder = "Email address",
                        value = email,
                        onValueChange = { if (it.length <= 254) { email = it; emailError = null } },
                        errorMessage = emailError,
                        maxLength = 254)
                    Spacer(modifier = Modifier.height(8.dp))

                    AuthInputField(placeholder = "Password",
                        value = password,
                        onValueChange = { if (it.length <= 64) { password = it; passwordError = null } },
                        isPassword = true,
                        errorMessage = passwordError,
                        maxLength = 64)
                    Spacer(modifier = Modifier.height(8.dp))

                    AuthInputField(placeholder = "Confirm Password",
                        value = confirmPassword,
                        onValueChange = { if (it.length <= 64) { confirmPassword = it; confirmPasswordError = null } },
                        isPassword = true,
                        errorMessage = confirmPasswordError,
                        maxLength = 64)
                    Spacer(modifier = Modifier.height(20.dp))

                    AnimatedVisibility(visible = bannerMessage != null) {
                        Column {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isBannerSuccess) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer)
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = bannerMessage ?: "",
                                    color = if (isBannerSuccess) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }

                    AuthPrimaryButton(text = "Create Account",
                        onClick = {
                            if (validateRegister()){
                                val enteredEmail = email
                                val enteredPassword = password
                                scope.launch {
                                    try {
                                        supabase.auth.signUpWith(Email){
                                            this.email = enteredEmail.trim()
                                            this.password = enteredPassword
                                        }
                                        isLogin = true
                                        password=""
                                        confirmPassword= ""
                                        clearFieldErrors()
                                        bannerMessage = "Account created. Check your email to confirm it."
                                        isBannerSuccess = true
                                    } catch (exception: Exception){
                                        bannerMessage= exception.message ?:"Could not create account."
                                        isBannerSuccess = false
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}



@Preview(showBackground = true)
@Composable
fun AScreenPreview() {
    AuthScreen()
}
