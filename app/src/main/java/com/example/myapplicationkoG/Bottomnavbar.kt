package com.example.myapplicationkoG

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.example.myapplicationkoG.ui.theme.Cyan
import com.example.myapplicationkoG.ui.theme.MidnightBlue

data class BottomNavItem(
    val route: String,
    val label: String,
    @DrawableRes val iconRes: Int
)

// The 5 items for your bottom navigation bar
val bottomNavItems = listOf(
    BottomNavItem(Screen.Home.route, "Home", R.drawable.home),
    BottomNavItem(Screen.Search.route, "Search", R.drawable.search),
    BottomNavItem(Screen.Upload.route, "Upload", R.drawable.upload),
    BottomNavItem(Screen.Studio.route, "Studio", R.drawable.studio),
    BottomNavItem(Screen.Profile.route, "Profile", R.drawable.profile)
)

val bottomBarRoutes = bottomNavItems.map { it.route }.toSet()

@Composable
fun BottomNavBar(
    navController: NavController,
    currentRoute: String?
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    NavigationBar(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .navigationBarsPadding(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        bottomNavItems.forEach { item ->
            val isSelected = currentRoute == item.route
            NavigationBarItem(
                selected = isSelected,
                onClick = {
                    if (!isSelected) {
                        navController.navigate(item.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = {
                    Icon(
                        painter = painterResource(id = item.iconRes),
                        contentDescription = item.label,
                        modifier = Modifier.size(30.dp)
                    )
                },
                label = {
                    Text(
                        text = item.label,
                        fontSize = 12.sp
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MidnightBlue,
                    selectedTextColor = if (isDark) Cyan else MidnightBlue,
                    indicatorColor = Cyan,
                    unselectedIconColor = Color.Gray,
                    unselectedTextColor = Color.Gray
                )
            )
        }
    }
}