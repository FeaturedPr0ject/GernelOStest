plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val stableKeystorePath = System.getenv("ROOT_PANIC_KEYSTORE_PATH")

android {
    namespace = "com.coneui.rootpanic"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.coneui.rootpanic"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    if (!stableKeystorePath.isNullOrBlank()) {
        signingConfigs {
            create("stable") {
                storeFile = file(stableKeystorePath)
                storePassword = System.getenv("ROOT_PANIC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ROOT_PANIC_KEY_ALIAS")
                keyPassword = System.getenv("ROOT_PANIC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (!stableKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
        getByName("release") {
            isMinifyEnabled = false
            if (!stableKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
