plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.camilo.st2mode"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.camilo.st2mode"
        minSdk = 31
        targetSdk = 36
        // Release CI passes these from the tag; local builds keep the defaults.
        versionCode = providers.gradleProperty("moonmode.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("moonmode.versionName").orNull ?: "1.0"
    }

    // Release signing comes from the environment so no key material lives in the repo.
    val releaseKeystore = providers.environmentVariable("MOONMODE_KEYSTORE_PATH").orNull
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("MOONMODE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("MOONMODE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("MOONMODE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // -Pmoonmode.debugSuffix=.dev installs beside a release build for device testing.
            providers.gradleProperty("moonmode.debugSuffix").orNull?.let { applicationIdSuffix = it }
        }
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.01")
    implementation(composeBom)
    // Material 3 Expressive: motion scheme and expressive theme.
    implementation("androidx.compose.material3:material3:1.5.0-alpha02")
    implementation("androidx.compose.material:material-icons-extended")
    // Backdrop blur for the floating glass control layer.
    implementation("dev.chrisbanes.haze:haze:1.6.10")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    testImplementation("junit:junit:4.13.2")
}
