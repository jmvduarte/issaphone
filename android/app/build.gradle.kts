plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // Capturas de ecrã automáticas dos ecrãs (sem telemóvel nem emulador)
    id("app.cash.paparazzi")
}

android {
    namespace = "pt.ipca.issafone"
    compileSdk = 34

    defaultConfig {
        applicationId = "pt.ipca.issafone"
        minSdk = 26
        targetSdk = 34
        // No GitHub Actions, cada build tem um número maior que o anterior:
        // é isso que permite à app saber que existe uma versão nova.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"

        // Repositório onde a app procura atualizações (Releases do GitHub)
        buildConfigField("String", "REPO_ATUALIZACOES", "\"jmvduarte/issaphone\"")
    }

    // Duas apps a partir do mesmo código:
    //  - IssaFone: já vem configurada para o Issabel do projeto (só pede extensão e palavra-passe)
    //  - IssaLink: genérica, para qualquer Issabel (pede também o endereço do servidor)
    flavorDimensions += "marca"
    productFlavors {
        create("issafone") {
            dimension = "marca"
            applicationId = "pt.ipca.issafone"
            resValue("string", "app_name", "IssaFone")
            buildConfigField("String", "NOME_APP", "\"IssaFone\"")
            buildConfigField("String", "SERVIDOR_PADRAO", "\"rsi-a36357.ddns.net\"")
            buildConfigField("String", "PREFIXO_RELEASE", "\"android-\"")
            buildConfigField("String", "NOME_APK", "\"IssaFone.apk\"")
            // O servidor do projeto tem SIP sobre TLS e SRTP obrigatórios
            buildConfigField("String", "TRANSPORTE_PADRAO", "\"tls\"")
        }
        create("issalink") {
            dimension = "marca"
            applicationId = "pt.ipca.issalink"
            resValue("string", "app_name", "IssaLink")
            buildConfigField("String", "NOME_APP", "\"IssaLink\"")
            buildConfigField("String", "SERVIDOR_PADRAO", "\"\"")
            buildConfigField("String", "PREFIXO_RELEASE", "\"issalink-\"")
            buildConfigField("String", "NOME_APK", "\"IssaLink.apk\"")
            // Qualquer Issabel: usa TLS se o servidor o tiver; senão UDP normal, como o Zoiper
            buildConfigField("String", "TRANSPORTE_PADRAO", "\"auto\"")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // Assinatura de release: só é usada se as variáveis existirem
    // (no GitHub vêm dos Secrets; localmente o Android Studio usa a chave de debug).
    val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
    signingConfigs {
        // Chave de debug fixa, guardada no repositório: todos os APK de teste ficam
        // com a mesma assinatura, condição para o Android aceitar atualizações.
        getByName("debug") {
            storeFile = file("issafone-debug.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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

    // O lint não deve impedir a compilação da versão final (os avisos ficam no relatório)
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }


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
    implementation("androidx.compose.material:material-icons-extended")   // ícones
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
    implementation("org.igniterealtime.smack:smack-experimental:4.4.8")   // anexos (HTTP File Upload)

    // Pré-visualização das imagens recebidas no chat
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Testes das capturas de ecrã
    testImplementation("junit:junit:4.13.2")
}
