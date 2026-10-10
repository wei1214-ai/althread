import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    // Bytecode pinned to 17: D8/R8 in AGP 8.7 cannot digest v64+ (JDK20/21) classes.
    // No pinned toolchain on purpose — any JDK 17+ running Gradle works
    // (Studio JBR 21, JDK 20, CI JDK 17); output is always v61.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.example.myapplicationkoG"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.myapplicationkoG"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        val openrouterKey: String = (localProps.getProperty("openrouterApiKey") as String?)
            ?: (project.findProperty("openrouterApiKey") as String?)
            ?: System.getenv("OPENROUTER_API_KEY") ?: ""
        buildConfigField("String", "OPENROUTER_API_KEY", "\"$openrouterKey\"")
        // HF tokens no longer baked into APK - VTON now proxies via Supabase Edge Function (AIConfig.VTON_URL)
        // Secrets are stored in Supabase Vault, not in BuildConfig.
        // Supabase publishable credentials: local.properties > -P gradle prop > env.
        // local.properties is gitignored. No real fallback values here on purpose:
        // committed defaults would ship in git history and every APK (decompilable).
        // A blank value fails fast at startup with a clear message instead.
        val supabaseUrl: String = (localProps.getProperty("supabaseUrl") as String?)
            ?: (project.findProperty("supabaseUrl") as String?)
            ?: System.getenv("SUPABASE_URL")
            ?: ""
        val supabaseKey: String = (localProps.getProperty("supabaseKey") as String?)
            ?: (project.findProperty("supabaseKey") as String?)
            ?: System.getenv("SUPABASE_KEY")
            ?: ""
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_KEY", "\"$supabaseKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        // Must stay 17 — see kotlin jvmTarget above.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Lifecycle / core (required by ContextCompat, FileProvider, collectAsStateWithLifecycle, viewModelScope)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Crop pics
    implementation("com.github.yalantis:ucrop:2.2.11")
    implementation("androidx.appcompat:appcompat:1.7.1")

    implementation(platform(libs.compose.bom))

    // Supabase
    implementation("io.github.jan-tennert.supabase:postgrest-kt:3.0.1")
    implementation("io.github.jan-tennert.supabase:auth-kt:3.0.1")
    implementation("io.github.jan-tennert.supabase:storage-kt:3.0.1")

    // Ktor Engine for Supabase
    implementation("io.ktor:ktor-client-android:3.0.1")

    // Kotlinx Serialization JSON
    implementation(libs.kotlinx.serialization.json)

    // Coil & Video Frame Support (keep same version to avoid NoSuchMethodError)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    // Activity & Compose UI
    implementation(libs.activity.compose)
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.material.icons.extended)

    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)

    // Navigation
    implementation(libs.navigation.compose)

    // Persistence
    implementation(libs.datastore.core)
    implementation(libs.datastore.preferences)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // NOTE: on-device inference removed — cutout now runs in the cloud
    // (`cutout` Edge Function, segformer). onnxruntime + opencv deps deleted
    // with the 169MB bundled model. Bitmap compositing is pure Android Canvas.

    // Media3 ExoPlayer dependencies for video playback (must match compileSdk 35)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
