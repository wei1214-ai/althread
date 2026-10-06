package com.example.myapplicationkoG

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage

val supabase: SupabaseClient = createSupabaseClient(
    supabaseUrl = "https://fqyixgfokvnvpudiruej.supabase.co",
    supabaseKey = "sb_publishable_XvUT9LxMnJ8qSJM6KVun5Q_pfUb-lUE"
) {
    install(Auth){
        scheme = "altthread"
        host = "login"
    }
    install(Postgrest)
    install(Storage)
}