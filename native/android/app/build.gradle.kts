plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "com.kotonara.farmcamera"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kotonara.farmcamera"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // domain / data は Android SDK に依存しない純粋ロジックなので core だけで足りる。
    // Dispatchers.Main が要る presentation 層は M3/M4 で -android を足す。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("junit:junit:4.13.2")
    // スケジューラを仮想時間で回すため。実時間を待つテストは当日落ちる。
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
