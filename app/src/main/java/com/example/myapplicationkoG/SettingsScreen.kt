package com.example.myapplicationkoG

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplicationkoG.ui.garmentinput.GarmentInputViewModel
import com.example.myapplicationkoG.ui.theme.Cyan
import com.example.myapplicationkoG.ui.theme.MidnightBlue
import com.example.myapplicationkoG.ui.theme.ThemeMode
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    selectedThemeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // Activity-scoped studio ViewModel: its staged garment files and drafts
    // survive navigation, so logout must wipe them to avoid leaking one
    // account's work into the next login on this device.
    val activity = context as? androidx.activity.ComponentActivity
    val garmentVm: GarmentInputViewModel? =
        if (activity != null) viewModel(viewModelStoreOwner = activity) else null
    var logoutError by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var isLoggingOut by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),

    ) {
        IconButton(onClick = onBack) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back to profile",
            tint = MaterialTheme.colorScheme.onBackground
        )
    }
        Text(
            text = "Settings",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.padding(top = 20.dp))

        Text(
            text = "App appearance",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.padding(top = 8.dp))

        ThemeOption(
            text = "Use phone setting",
            selected = selectedThemeMode == ThemeMode.SYSTEM,
            onClick = { onThemeModeChanged(ThemeMode.SYSTEM) }
        )

        ThemeOption(
            text = "Light mode",
            selected = selectedThemeMode == ThemeMode.LIGHT,
            onClick = { onThemeModeChanged(ThemeMode.LIGHT) }
        )

        ThemeOption(
            text = "Dark mode",
            selected = selectedThemeMode == ThemeMode.DARK,
            onClick = { onThemeModeChanged(ThemeMode.DARK) }
        )

        Spacer(modifier = Modifier.padding(top = 24.dp))

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (isLoggingOut) return@Button
                scope.launch {
                    isLoggingOut = true
                    logoutError = null
                    try {
                        supabase.auth.signOut()
                        // Clear per-account in-memory + disk cache to avoid cross-account leaks
                        com.example.myapplicationkoG.ShareDesignSession.clear()
                        com.example.myapplicationkoG.ui.editor.ChallengeSession.clear()
                        runCatching {
                            com.example.myapplicationkoG.ui.editor.DesignSession.clear()
                        }
                        runCatching { garmentVm?.clearForLogout() }
                        onLogout()
                    } catch (e: Exception) {
                        logoutError = e.message ?: "Logout failed. Please try again."
                        android.widget.Toast.makeText(context, logoutError, android.widget.Toast.LENGTH_LONG).show()
                    } finally {
                        isLoggingOut = false
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Cyan,
                contentColor = MidnightBlue
            )
        ) {
            Text(if (isLoggingOut) "Logging out..." else "Log out", fontWeight = FontWeight.Bold, color = MidnightBlue, fontSize = 16.sp)
        }
        if (logoutError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(logoutError ?: "", color = androidx.compose.ui.graphics.Color.Red, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ThemeOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = text,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}