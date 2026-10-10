package com.example.myapplicationkoG

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage

// Credentials come from BuildConfig (local.properties, never committed); fail fast when missing.
private val supabaseUrl: String = BuildConfig.SUPABASE_URL
    .takeIf { it.isNotBlank() }
    ?: error("Missing Supabase URL: define supabaseUrl in local.properties (see README).")
private val supabaseKey: String = BuildConfig.SUPABASE_KEY
    .takeIf { it.isNotBlank() }
    ?: error("Missing Supabase key: define supabaseKey in local.properties (see README).")

val supabase: SupabaseClient = createSupabaseClient(
    supabaseUrl = supabaseUrl,
    supabaseKey = supabaseKey
) {
    install(Auth){
        scheme = "altthread"
        host = "login"
    }
    install(Postgrest)
    install(Storage)
}