package com.example.myapplicationkoG

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import com.example.myapplicationkoG.ui.editor.DesignSession
import com.example.myapplicationkoG.ui.editor.DyeState
import com.example.myapplicationkoG.ui.editor.EditorPlaceHolder
import com.example.myapplicationkoG.ui.editor.PlacedButton
import com.example.myapplicationkoG.ui.garmentinput.GarmentInputScreen
import com.example.myapplicationkoG.ui.garmentinput.GarmentInputViewModel
import com.example.myapplicationkoG.ui.theme.AltThreadTheme
import com.example.myapplicationkoG.ui.theme.MidnightBlue
import com.example.myapplicationkoG.ui.theme.ThemeMode
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val garmentInputViewModel: GarmentInputViewModel by viewModels()
    private var sharedPostIdState = mutableStateOf<String?>(null)
    private var passwordRecoveryState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supabase.handleDeeplinks(intent)
        extractPostIdFromIntent(intent)
        extractPasswordRecoveryFromIntent(intent)

        enableEdgeToEdge()
        setContent {
            var savedThemeMode by rememberSaveable {
                mutableStateOf(ThemeMode.SYSTEM.name)
            }

            val themeMode = runCatching { ThemeMode.valueOf(savedThemeMode) }.getOrElse { ThemeMode.SYSTEM }

            AltThreadTheme(themeMode = themeMode) {
                val sharedPostId = sharedPostIdState.value

                AltThreadApp(
                    garmentInputVm = garmentInputViewModel,
                    sharedPostId = sharedPostId,
                    onDismissSharedPost = { sharedPostIdState.value = null },
                    passwordRecoveryRequested = passwordRecoveryState.value,
                    onDismissPasswordRecovery = { passwordRecoveryState.value = false },
                    onOpenChallengeSource = { sourcePost -> sharedPostIdState.value = sourcePost.id },
                    themeMode = themeMode,
                    onThemeModeChanged = { newThemeMode ->
                        savedThemeMode = newThemeMode.name
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        supabase.handleDeeplinks(intent)
        extractPostIdFromIntent(intent)
        extractPasswordRecoveryFromIntent(intent)
    }

    private fun extractPostIdFromIntent(intent: Intent?) {
        val data: Uri? = intent?.data
        if (data != null && data.scheme == "altthread" && data.host == "post") {
            val postId = data.lastPathSegment
            if (!postId.isNullOrEmpty()) {
                sharedPostIdState.value = postId
            }
        }
    }

    private fun extractPasswordRecoveryFromIntent(intent: Intent?) {
        val data = intent?.data
        val isRecoveryLink = data?.getQueryParameter("type") == "recovery" ||
                data?.fragment?.contains("type=recovery") == true

        if (
            data?.scheme == "altthread" &&
            data.host == "login" &&
            (data.path == "/reset-password" || isRecoveryLink)
        ) {
            passwordRecoveryState.value = true
        }
    }
}

@Composable
fun AltThreadApp(
    garmentInputVm: GarmentInputViewModel,
    sharedPostId: String? = null,
    onDismissSharedPost: () -> Unit = {},
    passwordRecoveryRequested: Boolean = false,
    onDismissPasswordRecovery: () -> Unit = {},
    onOpenChallengeSource: ((Post) -> Unit)? = null,
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var sharedPost by remember { mutableStateOf<Post?>(null) }
    var postsRefreshKey by remember { mutableIntStateOf(0) }
    var homeChallengeFilter by remember { mutableStateOf("For You") }
    var isLoadingSharedPost by remember { mutableStateOf(false) }
    val postRepository = remember { PostRepository() }

    LaunchedEffect(passwordRecoveryRequested) {
        if (passwordRecoveryRequested) {
            navController.navigate(Screen.ResetPassword.route) {
                launchSingleTop = true
            }
        }
    }

    val navigateToOtherUserProfile = { userId:String, username: String, avatarUrl: String ->
        val encodedUsername = URLEncoder.encode(username, StandardCharsets.UTF_8.toString())
        val encodedAvatarUrl = URLEncoder.encode(
            avatarUrl.ifEmpty { "https://via.placeholder.com/150" },
            StandardCharsets.UTF_8.toString()
        )
        val encodedUserId = URLEncoder.encode(
            userId,
            StandardCharsets.UTF_8.toString()
        )

        navController.navigate("other_user_profile/$encodedUserId/$encodedUsername/$encodedAvatarUrl")
    }

    val navigateToUserProfile = { userId: String, username: String, avatarUrl: String ->
        val currentUserId = supabase.auth.currentUserOrNull()?.id
        if (userId == currentUserId) {
            navController.navigate(Screen.Profile.route) { launchSingleTop = true }
        } else {
            navigateToOtherUserProfile(userId, username, avatarUrl)
        }
    }

    LaunchedEffect(sharedPostId) {
        if (!sharedPostId.isNullOrEmpty()) {
            isLoadingSharedPost = true
            try {
                sharedPost = postRepository.getPostById(sharedPostId)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoadingSharedPost = false
            }
        } else {
            sharedPost = null
        }
    }

    if (sharedPostId != null) {
        Dialog(onDismissRequest = onDismissSharedPost) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isLoadingSharedPost) {
                    CircularProgressIndicator(color = MidnightBlue)
                } else if (sharedPost != null) {
                    PostCard(
                        post = sharedPost!!,
                        onUserClick = {userId, username, avatarUrl ->
                            onDismissSharedPost()
                            navigateToUserProfile(userId, username, avatarUrl)
                        },
                        onOpenChallengeSource = onOpenChallengeSource
                    )
                } else {
                    onDismissSharedPost()
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (currentRoute in bottomBarRoutes && currentRoute != Screen.Upload.route && currentRoute?.startsWith("other_user_profile") != true) {
                BottomNavBar(navController = navController, currentRoute = currentRoute)
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Auth.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Auth.route) {
                AuthScreen(
                    onLoginSuccess = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Auth.route) { inclusive = true }
                        }
                    },
                    onForgotPassword = {
                        navController.navigate(Screen.ForgotPassword.route)
                    }
                )
            }
            composable(Screen.ForgotPassword.route) {
                ForgotPasswordScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.ResetPassword.route) {
                ResetPasswordScreen(
                    onPasswordUpdated = {
                        onDismissPasswordRecovery()
                        navController.navigate(Screen.Auth.route) {
                            popUpTo(Screen.Auth.route) { inclusive = true }
                        }
                    }
                )
            }
            composable(Screen.Upload.route) {
                UploadScreen(
                    onClose = {
                        postsRefreshKey++
                        navController.popBackStack()
                    }
                )
            }
            composable(Screen.Home.route) {
                HomeScreen(
                    refreshKey = postsRefreshKey,
                    initialFilter = homeChallengeFilter,
                    onUserClick = { userId,username,avatarUrl ->
                        navigateToUserProfile(userId, username, avatarUrl)
                    },
                    onAcceptChallenge = { post ->
                        val ctx = navController.context
                        scope.launch {
                            try {
                                val urls = post.allMediaUrls.ifEmpty { listOf(post.mediaUrl) }
                                val rawFront = urls.getOrNull(0)?.trim().orEmpty()
                                if (rawFront.isBlank()) {
                                    android.widget.Toast.makeText(ctx, "No garment image", android.widget.Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                val rawBack = urls.getOrNull(1)?.trim().orEmpty().ifBlank { rawFront }
                                android.widget.Toast.makeText(ctx, "Loading challenge...", android.widget.Toast.LENGTH_SHORT).show()
                                val frontFile = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { downloadUrlToFile(ctx, rawFront, "challenge_front") }
                                val backFile = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { downloadUrlToFile(ctx, rawBack, "challenge_back") }
                                if (frontFile.length() == 0L || backFile.length() == 0L) error("Downloaded file is empty")
                                // Use ViewModel for rotation survival (singleton delegates to same SessionHolder for compat)
                                runCatching { garmentInputVm.stageChallenge(post.id, post.clothingTitle, post.caption) }
                                    .onFailure { com.example.myapplicationkoG.ui.editor.ChallengeSession.stage(post.id, post.clothingTitle, post.caption) }
                                garmentInputVm.clearAll(deleteFiles = false)
                                garmentInputVm.loadDesignPaths(frontFile, backFile)
                                navController.navigate(Screen.Editor.route)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                val msg = e.message?.takeIf { it.isNotBlank() } ?: e.toString()
                                android.widget.Toast.makeText(ctx, "Failed to load challenge: $msg", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onOpenChallengeSource = onOpenChallengeSource
                )
            }
            composable(Screen.Search.route) { SearchScreen() }
            composable(Screen.Studio.route) {
                StudioScreen(
                    onStartDesign = {
                        runCatching { garmentInputVm.clearChallenge() }
                        // keep singleton in sync for backward compat
                        com.example.myapplicationkoG.ui.editor.ChallengeSession.title = null
                        com.example.myapplicationkoG.ui.editor.ChallengeSession.description = null
                        garmentInputVm.clearAll(deleteFiles = false)
                        navController.navigate(Screen.GarmentInput.route)
                    },
                    onAcceptChallenge = {
                        homeChallengeFilter = "Challenge"
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Studio.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onContinueDesign = {
                        navController.navigate(Screen.ContinueDesigns.route)
                    }
                )
            }
            composable(Screen.Profile.route) {
                ProfileScreen(
                    onEditProfile = { navController.navigate(Screen.EditProfile.route) },
                    onShowFollowers = { userId -> navController.navigate("followers/$userId") },
                    onShowFollowing = { userId -> navController.navigate("following/$userId") },
                    onOpenSetting = { navController.navigate(Screen.Settings.route)}
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    selectedThemeMode = themeMode,
                    onThemeModeChanged = onThemeModeChanged,
                    onBack = {
                        navController.popBackStack()
                    },
                    onLogout = {
                        navController.navigate(Screen.Auth.route){
                            popUpTo(0){ // Clear navigation history,
                                // after logout user press back button wont
                                // go back to previous screen
                                inclusive = true
                            }
                        }
                    }
                )
            }
            composable(Screen.EditProfile.route) {
                EditProfileScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = "other_user_profile/{userId}/{username}/{avatarUrl}",
                arguments = listOf(
                    navArgument("userId") { type = NavType.StringType },
                    navArgument("username") { type = NavType.StringType },
                    navArgument("avatarUrl") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val encodedUserId = backStackEntry.arguments?.getString("userId") ?: ""
                val encodedUsername = backStackEntry.arguments?.getString("username") ?: ""
                val encodedAvatarUrl = backStackEntry.arguments?.getString("avatarUrl") ?: ""

                val decodedUserId = URLDecoder.decode(encodedUserId, StandardCharsets.UTF_8.toString())
                val decodedUsername = URLDecoder.decode(encodedUsername, StandardCharsets.UTF_8.toString())
                val decodedAvatarUrl = URLDecoder.decode(encodedAvatarUrl, StandardCharsets.UTF_8.toString())

                OtherUserProfileScreen(
                    userId = decodedUserId,
                    username = decodedUsername,
                    avatarUrl = decodedAvatarUrl,
                    onBackClick = { navController.popBackStack() },
                    onShowFollowers = { userId -> navController.navigate("followers/$userId") },
                    onShowFollowing = { userId -> navController.navigate("following/$userId") }
                )
            }

            composable(
                route = "followers/{userId}",
                arguments = listOf(navArgument("userId") { type = NavType.StringType })
            ) { backStackEntry ->
                val userId = backStackEntry.arguments?.getString("userId") ?: ""
                FollowListScreen(
                    userId = userId,
                    showFollowers = true,
                    onBackClick = { navController.popBackStack() },
                    onUserClick = { selectedUserId, username, avatarUrl ->
                        navigateToUserProfile(selectedUserId, username, avatarUrl)
                    }
                )
            }

            composable(
                route = "following/{userId}",
                arguments = listOf(navArgument("userId") { type = NavType.StringType })
            ) { backStackEntry ->
                val userId = backStackEntry.arguments?.getString("userId") ?: ""
                FollowListScreen(
                    userId = userId,
                    showFollowers = false,
                    onBackClick = { navController.popBackStack() },
                    onUserClick = { selectedUserId, username, avatarUrl ->
                        navigateToUserProfile(selectedUserId, username, avatarUrl)
                    }
                )
            }

            composable(Screen.ContinueDesigns.route) {
                val ctx = LocalContext.current
                ContinueDesignsScreen(
                    onOpenDesign = { row ->
                        val repo = DesignRepository()
                        scope.launch {
                            val (front, back) = repo.ensureLocalFiles(ctx, row)
                            runCatching { garmentInputVm.clearChallenge() }
                            com.example.myapplicationkoG.ui.editor.ChallengeSession.postId = null
                            garmentInputVm.openSavedDesign(
                                id = row.id,
                                name = row.name,
                                front = front,
                                back = back,
                                challengePostId = row.state.challengePostId
                            )
                            val dyeMap = row.state.dye.mapKeys { entry ->
                                runCatching { com.example.myapplicationkoG.domain.model.GarmentSideId.valueOf(entry.key) }.getOrElse { com.example.myapplicationkoG.domain.model.GarmentSideId.FRONT }
                            }.mapValues { (_, v) ->
                                DyeState(color = androidx.compose.ui.graphics.Color(v.color), strength = v.strength)
                            }
                            val buttonMap = row.state.buttons.mapKeys { entry ->
                                runCatching { com.example.myapplicationkoG.domain.model.GarmentSideId.valueOf(entry.key) }.getOrElse { com.example.myapplicationkoG.domain.model.GarmentSideId.FRONT }
                            }.mapValues { (_, list) ->
                                list.map { PlacedButton(pos = Offset(it.x, it.y), scale = it.scale, color = androidx.compose.ui.graphics.Color(it.color), style = it.style, rotation = it.rotation) }
                            }
                            // Use ViewModel for rotation survival (singleton kept for backward compat)
                            runCatching { garmentInputVm.stageDesignTyped(dyeMap, buttonMap) }
                                .onFailure { DesignSession.stage(dye = dyeMap, buttons = buttonMap) }
                            navController.navigate(Screen.Editor.route)
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.GarmentInput.route) {
                GarmentInputScreen(
                    viewModel = garmentInputVm,
                    onOpenEditor = { navController.navigate(Screen.Editor.route) },
                    onBack = {
                        // Defer deletion: keep cache files until explicit clear or new design loaded (LRU)
                        garmentInputVm.clearForBack()
                        navController.popBackStack()
                    },
                )
            }
            composable(Screen.Editor.route) {
                EditorPlaceHolder(
                    viewModel = garmentInputVm,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

private suspend fun downloadUrlToFile(context: android.content.Context, url: String, prefix: String): java.io.File {
    return with(kotlinx.coroutines.Dispatchers.IO) {
        val bytes = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("Download failed: ${resp.code}")
            resp.body?.bytes() ?: error("Empty body")
        }
        val out = java.io.File(context.cacheDir, "${prefix}_${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
        out.writeBytes(bytes)
        out
    }
}
