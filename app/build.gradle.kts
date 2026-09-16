import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun quotedBuildConfig(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun mentalHealthConfigFromSibling(name: String): String? {
    val candidates = listOf(
        rootProject.file("../mental_health-_app/app/build.gradle.kts"),
        rootProject.file("../mental_health_app/app/build.gradle.kts")
    )
    val pattern = Regex(
        """buildConfigField\(\s*\"String\"\s*,\s*\"${Regex.escape(name)}\"\s*,\s*\"\\\\\"(.*?)\\\\\"\"\s*\)"""
    )
    return candidates.firstNotNullOfOrNull { file ->
        if (!file.exists()) null else pattern.find(file.readText())?.groupValues?.getOrNull(1)
    }
}

val mentalHealthFlowiseUrl =
    localProperties.getProperty("MENTAL_HEALTH_FLOWISE_URL")
        ?: mentalHealthConfigFromSibling("FLOWISE_URL")
        ?: "https://YOUR-FLOWISE-HOST/api/v1/prediction/YOUR-CHATFLOW-ID"

val mentalHealthFlowiseApiKey =
    localProperties.getProperty("MENTAL_HEALTH_FLOWISE_API_KEY")
        ?: mentalHealthConfigFromSibling("FLOWISE_API_KEY")
        ?: "DUMMY_REPLACE_ME"

android {
    namespace = "org.tarasec.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.tarasec.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 11
        versionName = "0.3.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "MENTAL_HEALTH_FLOWISE_URL", quotedBuildConfig(mentalHealthFlowiseUrl))
        buildConfigField("String", "MENTAL_HEALTH_FLOWISE_API_KEY", quotedBuildConfig(mentalHealthFlowiseApiKey))
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(keystoreProperties.getProperty("storeFile")))
                storePassword = requireNotNull(keystoreProperties.getProperty("storePassword"))
                keyAlias = requireNotNull(keystoreProperties.getProperty("keyAlias"))
                keyPassword = requireNotNull(keystoreProperties.getProperty("keyPassword"))
            }
        }
    }

    buildTypes {
        release {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("com.google.zxing:core:3.5.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
