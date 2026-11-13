import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

// Local-only fallback shared with the admin panel. admin/config.js is gitignored,
// so credentials never enter source control.
val localAdminConfig = rootProject.file("admin/config.js").takeIf { it.exists() }?.readText().orEmpty()
fun adminConfigValue(name: String): String =
    Regex("$name\\s*:\\s*['\"]([^'\"]+)['\"]").find(localAdminConfig)?.groupValues?.get(1).orEmpty()
val supabaseUrl = localProperties.getProperty("SUPABASE_URL")?.takeIf { it.isNotBlank() }
    ?: adminConfigValue("supabaseUrl")
val supabaseAnonKey = localProperties.getProperty("SUPABASE_ANON_KEY")?.takeIf { it.isNotBlank() }
    ?: adminConfigValue("supabaseAnonKey")

val uploadProperties = Properties().apply {
    val path = System.getenv("ZOMEAL_SIGNING_PROPERTIES")
    if (!path.isNullOrBlank()) file(path).inputStream().use(::load)
}
android {
    signingConfigs {
        if (uploadProperties.isNotEmpty()) create("upload") {
            storeFile = file(uploadProperties.getProperty("provider.storeFile"))
            storePassword = uploadProperties.getProperty("provider.storePassword")
            keyAlias = uploadProperties.getProperty("provider.keyAlias")
            keyPassword = uploadProperties.getProperty("provider.keyPassword")
        }
    }
    namespace = "com.zomeal.provider"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.zomeal.provider"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        buildConfigField("boolean", "DEVELOPMENT_AUTH", "false")
        // Public endpoint only. Never place Google credentials in the APK.
        buildConfigField("String", "KITCHEN_VOICE_URL", "\"${localProperties.getProperty("KITCHEN_VOICE_URL", "").replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }
    buildTypes {
        debug { buildConfigField("boolean", "DEVELOPMENT_AUTH", "true") }
        release {
            buildConfigField("boolean", "DEVELOPMENT_AUTH", "false")
            if (uploadProperties.isNotEmpty()) signingConfig = signingConfigs.getByName("upload")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.gms:play-services-auth-api-phone:18.2.0")
    implementation(platform("com.google.firebase:firebase-bom:34.18.0"))
    implementation("com.google.firebase:firebase-messaging")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
