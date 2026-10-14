plugins { alias(libs.plugins.android.application) }

android {
    namespace = "io.qingledger.paymentsimulator"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.qingledger.paymentsimulator"
        minSdk = 30
        targetSdk = 37
        versionCode = 3
        versionName = "0.3.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
androidComponents.beforeVariants(androidComponents.selector().withBuildType("release")) { it.enable = false }
