import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val localAdminConfig = rootProject.file("admin/config.js").takeIf { it.exists() }?.readText().orEmpty()
fun adminConfigValue(name: String): String = Regex("$name\\s*:\\s*['\"]([^'\"]+)['\"]").find(localAdminConfig)?.groupValues?.get(1).orEmpty()
val supabaseUrl = localProperties.getProperty("SUPABASE_URL")?.takeIf(String::isNotBlank) ?: adminConfigValue("supabaseUrl")
val supabaseAnonKey = localProperties.getProperty("SUPABASE_ANON_KEY")?.takeIf(String::isNotBlank) ?: adminConfigValue("supabaseAnonKey")
android {
    namespace = "com.zomeal.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zomeal.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        buildConfigField("boolean", "DEVELOPMENT_AUTH", "false")
    }

    buildTypes {
        debug { buildConfigField("boolean", "DEVELOPMENT_AUTH", "true") }
        release { buildConfigField("boolean", "DEVELOPMENT_AUTH", "false") }
    }

    buildFeatures { compose = true; buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.gms:play-services-auth-api-phone:18.2.0")
    implementation("com.razorpay:checkout:1.6.40")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
