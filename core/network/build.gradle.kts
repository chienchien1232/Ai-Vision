plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.rayban.ai.core.network"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    implementation(project(":core:common"))
}
