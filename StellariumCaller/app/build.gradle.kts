plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "jv.stellariumcaller.stellariumcaller"
    compileSdk = 37

defaultConfig {
        applicationId = "jv.stellariumcaller.stellariumcaller"
        minSdk = 30
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val keystorePath = System.getenv("KEYSTORE_PATH") ?: System.getProperty("keystore.path", "")
    if (keystorePath.isNotEmpty()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: System.getProperty("keystore.password", "")
                keyAlias = System.getenv("KEY_ALIAS") ?: System.getProperty("keystore.alias", "")
                keyPassword = System.getenv("KEY_PASSWORD") ?: System.getProperty("keystore.password", "")
            }
        }
    }

    buildTypes {
        release {
            if (keystorePath.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = true
            }
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core)
    implementation(libs.okhttp)
    implementation(libs.kopus)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.media)
    implementation(libs.media3.exoplayer)
    implementation(libs.kotlin.stdlib)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)
    implementation(libs.activity.compose)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
