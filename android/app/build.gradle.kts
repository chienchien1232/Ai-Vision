import java.io.File
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.ai_vision"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.ai_vision"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    androidResources { noCompress += listOf("ort") }
    // Native inference is large: distribute a single 64-bit ABI per APK.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/asrAssets").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/ttsAssets").get().asFile)
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/asrTestAssets").get().asFile)
}

// Pinned upstream assets: no API key or runtime network access is needed for STT.
val prepareSpeechModels by tasks.registering {
    notCompatibleWithConfigurationCache("Uses Gradle archive extraction for pinned speech models")
    val output = layout.buildDirectory.dir("generated/asrAssets")
    outputs.dir(output)
    outputs.dir(layout.buildDirectory.dir("generated/asrTestAssets"))
    inputs.property("models", "moonshine-v2-2026-02-27-with-smoke-fixtures")
    doLast {
        val models = listOf(
            Triple("vi", "sherpa-onnx-moonshine-base-vi-quantized-2026-02-27", "97b53f5fb75a2a9dd6327440a34f45fc06c927ed2fa7217e5ebe54058e269416"),
            Triple("en", "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27", "9ec31b342d8fa3240c3b81b8f82e1cf7e3ac467c93ca5a999b741d5887164f8d")
        )
        val cache = layout.buildDirectory.dir("modelDownloads").get().asFile.apply { mkdirs() }
        for ((language, name, checksum) in models) {
            val archive = cache.resolve("$name.tar.bz2")
            fun digest(file: File): String {
                val sha = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        sha.update(buffer, 0, size)
                    }
                }
                return sha.digest().joinToString("") { "%02x".format(it) }
            }
            if (!archive.isFile || digest(archive) != checksum) {
                logger.lifecycle("Downloading local speech model: $language")
                val connection = URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$name.tar.bz2").toURL().openConnection()
                connection.connectTimeout = 15000
                connection.readTimeout = 120000
                connection.getInputStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
            }
            check(digest(archive) == checksum) { "Speech model checksum mismatch: $name" }
            copy {
                from(tarTree(resources.bzip2(archive)))
                include("$name/encoder_model.ort", "$name/decoder_model_merged.ort", "$name/tokens.txt", "$name/LICENSE")
                eachFile { path = "asr/$language/${this.name}" }
                includeEmptyDirs = false
                into(output)
            }
            check(output.get().file("asr/$language/encoder_model.ort").asFile.isFile) { "Missing speech model: $language" }
            copy {
                from(tarTree(resources.bzip2(archive)))
                include("$name/test_wavs/0.wav")
                eachFile { path = "asr-smoke/$language.wav" }
                includeEmptyDirs = false
                into(layout.buildDirectory.dir("generated/asrTestAssets"))
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(prepareSpeechModels) }

val prepareVietnameseTts by tasks.registering(Exec::class) {
    inputs.files(rootProject.file("tools/prepare_vi_tts.py"), rootProject.file("tools/vi-tts-notices.txt"))
    inputs.property("model", "vais1000-fa136771-v1")
    outputs.dir(layout.buildDirectory.dir("generated/ttsAssets"))
    commandLine("python", rootProject.file("tools/prepare_vi_tts.py").absolutePath,
        "--output", layout.buildDirectory.dir("generated/ttsAssets").get().asFile.absolutePath,
        "--cache", layout.buildDirectory.dir("ttsDownloads").get().asFile.absolutePath)
}
tasks.named("preBuild") { dependsOn(prepareVietnameseTts) }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("com.k2fsa:sherpa-onnx:1.13.8@aar")
    // Bundled Latin OCR: works on the first run offline, without a Play Services model download.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
