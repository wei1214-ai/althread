package com.example.myapplicationkoG

sealed class Screen(val route: String) {
    object Auth : Screen("auth")
    object Upload : Screen("upload")
    object Home : Screen("home")
    object Search : Screen("search")
    object Studio : Screen("studio")
    object Profile : Screen("profile")
    object EditProfile : Screen("edit_profile")
    object Settings : Screen("settings")
    object ForgotPassword : Screen("forgot_password")
    object ResetPassword : Screen("reset_password")

    // Part 1 editor flow
    object GarmentInput : Screen("garment_input")
    object Editor : Screen("editor")
    
    object ContinueDesigns : Screen("continue_designs")
    object SelectDesignForShare : Screen("select_design_for_share")
}
