plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

val qaBuild = providers.gradleProperty("qingledger.qa").orNull == "true"

android {
    namespace = "io.qingledger.app"
    compileSdk = 37

    defaultConfig {
        applicationId = if (qaBuild) "io.qingledger.app.qa" else "io.qingledger.app"
        manifestPlaceholders["appLabel"] = if (qaBuild) "轻账验证" else "轻账"
        minSdk = 30
        targetSdk = 37
        versionCode = 48
        versionName = "0.8.1"
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
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":automation"))
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.core)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation(libs.miuix.ui)
    debugImplementation("dev.rikka.shizuku:api:13.1.5")
}
