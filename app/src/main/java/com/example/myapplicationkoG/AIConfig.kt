package com.example.myapplicationkoG

/**
 * Supabase AI endpoint configuration.
 *
 * Uses the same publishable key as the PostgREST client to avoid auth drift.
 *
 * Note: BuildConfig fields are not compile-time constants, hence `val` rather than `const val`.
 */
object AIConfig {
    val SUPABASE_URL: String get() = BuildConfig.SUPABASE_URL
    val SUPABASE_ANON_KEY: String get() = BuildConfig.SUPABASE_KEY
    val SUPABASE_FUNCTION_URL: String get() = "${BuildConfig.SUPABASE_URL}/functions/v1/ai-chat"
    val VTON_URL: String get() = "${BuildConfig.SUPABASE_URL}/functions/v1/vton"
    val CUTOUT_URL: String get() = "${BuildConfig.SUPABASE_URL}/functions/v1/cutout"
}