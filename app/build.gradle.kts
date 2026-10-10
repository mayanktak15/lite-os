import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

fun loadDotEnv(file: File): Map<String, String> {
    if (!file.exists() || !file.isFile) return emptyMap()
    val map = mutableMapOf<String, String>()
    file.useLines { lines ->
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val delimiterIndex = line.indexOf('=')
            if (delimiterIndex > 0) {
                val key = line.substring(0, delimiterIndex).trim()
                var value = line.substring(delimiterIndex + 1).trim()
                if ((value.startsWith("\"") && value.endsWith("\"")) ||
                    (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length - 1)
                }
                map[key] = value
            }
        }
    }
    return map
}

fun sanitizeEnvValue(value: String): String {
    val trimmed = value.trim()
    return if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
        (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
        trimmed.substring(1, trimmed.length - 1).trim()
    } else {
        trimmed
    }
}

val defaultRemoteConfigUrl = "https://mayanktak15.github.io/lite-os/config.json"
val envFile = rootProject.file(".env").takeIf { it.exists() } ?: file(".env")
val dotEnvMap = if (envFile.exists()) loadDotEnv(envFile) else emptyMap()

val rawRemoteConfigUrl = providers.environmentVariable("LITE_TV_REMOTE_CONFIG_URL").orNull?.takeIf { it.isNotBlank() }
    ?: System.getenv("LITE_TV_REMOTE_CONFIG_URL")?.takeIf { it.isNotBlank() }
    ?: providers.gradleProperty("LITE_TV_REMOTE_CONFIG_URL").orNull?.takeIf { it.isNotBlank() }
    ?: dotEnvMap["LITE_TV_REMOTE_CONFIG_URL"]?.takeIf { it.isNotBlank() }
    ?: defaultRemoteConfigUrl

val liteTvRemoteConfigUrl = sanitizeEnvValue(rawRemoteConfigUrl).ifBlank { defaultRemoteConfigUrl }

android {
    namespace = "com.litetv.app"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.litetv.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        val escapedConfigUrl = liteTvRemoteConfigUrl.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "LITE_TV_REMOTE_CONFIG_URL", "\"$escapedConfigUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)

    // Android TV / Leanback
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.recyclerview)

    // Media3 ExoPlayer
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)

    // Kotlin Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Unit Testing
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
