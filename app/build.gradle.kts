import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.rayban.ai"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rayban.ai"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GEMINI_API_KEY", "\"${geminiApiKey()}\"")
        val sttModel = providers.gradleProperty("GEMINI_STT_MODEL").getOrElse("gemini-3.6-flash")
        require(sttModel.matches(Regex("[a-zA-Z0-9.-]+")))
        buildConfigField("String", "GEMINI_STT_MODEL", "\"$sttModel\"")
        val ttsModel = providers.gradleProperty("GEMINI_TTS_MODEL").getOrElse("gemini-3.1-flash-tts-preview")
        val ttsVoice = providers.gradleProperty("GEMINI_TTS_VOICE").getOrElse("Kore")
        require(ttsModel.matches(Regex("[a-zA-Z0-9.-]+")) && ttsVoice.matches(Regex("[a-zA-Z]+")))
        buildConfigField("String", "GEMINI_TTS_MODEL", "\"$ttsModel\"")
        buildConfigField("String", "GEMINI_TTS_VOICE", "\"$ttsVoice\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

fun geminiApiKey(): String {
    val propertiesFile = rootProject.file("local.properties")
    if (!propertiesFile.exists()) return ""
    val properties = Properties()
    propertiesFile.inputStream().use { properties.load(it) }
    return properties.getProperty("GEMINI_API_KEY", "").trim()
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:camera"))
    implementation(project(":core:audio"))
    implementation(project(":core:voice"))
    implementation(project(":data"))
    implementation(project(":domain"))
    implementation(project(":feature"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
