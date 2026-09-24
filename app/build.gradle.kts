plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.crewschedule"
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "com.example.crewschedule"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures { compose = true; buildConfig = true }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    buildFeatures { buildConfig = true }

    defaultConfig {
        val supabaseUrl = project.findProperty("supabaseUrl")?.toString()?.takeIf { it.isNotBlank() }
            ?: System.getenv("SUPABASE_URL")?.takeIf { it.isNotBlank() }
            ?: ""
        val supabaseAnonKey = project.findProperty("supabaseAnonKey")?.toString()?.takeIf { it.isNotBlank() }
            ?: System.getenv("SUPABASE_ANON_KEY")?.takeIf { it.isNotBlank() }
            ?: ""
        val scheduleId = project.findProperty("scheduleId")?.toString()?.takeIf { it.isNotBlank() }
            ?: System.getenv("SCHEDULE_ID")?.takeIf { it.isNotBlank() }
            ?: "crew-schedule-shared"

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        buildConfigField("String", "SCHEDULE_ID", "\"$scheduleId\"")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
