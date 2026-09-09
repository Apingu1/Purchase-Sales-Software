plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val ciVersionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val ciKeystorePath = System.getenv("PURCHASE_SALES_KEYSTORE_PATH")

android {
    namespace = "com.apingu.purchasesales"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.apingu.purchasesales"
        minSdk = 28
        targetSdk = 35
        versionCode = ciVersionCode ?: 2
        versionName = if (ciVersionCode != null) "1.1.$ciVersionCode" else "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (!ciKeystorePath.isNullOrBlank()) {
            create("ciUpdate") {
                storeFile = file(ciKeystorePath)
                storePassword = System.getenv("PURCHASE_SALES_KEYSTORE_PASSWORD") ?: "purchasesales-ci"
                keyAlias = System.getenv("PURCHASE_SALES_KEY_ALIAS") ?: "purchasesales"
                keyPassword = System.getenv("PURCHASE_SALES_KEY_PASSWORD") ?: "purchasesales-ci"
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            if (!ciKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("ciUpdate")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
