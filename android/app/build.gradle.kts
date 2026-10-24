plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "pt.ipca.issafone"
    compileSdk = 34

    defaultConfig {
        applicationId = "pt.ipca.issafone"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Assinatura de release: só é usada se as variáveis existirem
    // (no GitHub vêm dos Secrets; localmente o Android Studio usa a chave de debug).
    val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST"
            )
        }
    }
}

// O Android já traz o parser XmlPull; o xpp3 do Smack entra em conflito.
configurations.all {
    exclude(group = "xpp3", module = "xpp3")
}

dependencies {
    // Interface (Jetpack Compose)
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")   // saber se a app está visível
    implementation("androidx.core:core-ktx:1.13.1")                // ServiceCompat, CallStyle

    // Chamadas SIP -> Asterisk do Issabel
    implementation("org.linphone:linphone-sdk-android:5.3.+")

    // Chat XMPP -> Openfire
    implementation("org.igniterealtime.smack:smack-android-extensions:4.4.8")
    implementation("org.igniterealtime.smack:smack-tcp:4.4.8")
}
