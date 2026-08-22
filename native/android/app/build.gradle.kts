import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jlleitschuh.gradle.ktlint")
}

// M2疎通スパイクの WEB_CLIENT_ID（drive.appdata のサインインに使う GCP の Web 用クライアント ID）。
// GCP の値なのでコミットしない。ビルド機ごとの local.properties にだけ置く（→ docs/00 Q11, issue #11）。
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.kotonara.farmcamera"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kotonara.farmcamera"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "WEB_CLIENT_ID",
            "\"${localProperties.getProperty("WEB_CLIENT_ID", "")}\""
        )
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }

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

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // domain / data は Android SDK に依存しない純粋ロジックなので core だけで足りる。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // M2疎通スパイク: サインイン（Credential Manager）と drive.appdata の認可（AuthorizationClient）。
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.0")
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    // AuthorizationClient は Task ベースなので suspend で await するために使う。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // M2疎通スパイク: プレビューなしで1枚撮る CameraX。
    implementation("androidx.camera:camera-core:1.5.1")
    implementation("androidx.camera:camera-camera2:1.5.1")
    implementation("androidx.camera:camera-lifecycle:1.5.1")

    // MainActivity を ComponentActivity にして registerForActivityResult / lifecycleScope を使う。
    implementation("androidx.activity:activity-ktx:1.12.4")

    // M3: Foreground Service（CaptureService）。LifecycleService で lifecycleScope を使い、
    // ServiceCompat.startForeground() で foregroundServiceType を API 差異を吸収して指定する。
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")

    testImplementation("junit:junit:4.13.2")
    // スケジューラを仮想時間で回すため。実時間を待つテストは当日落ちる。
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // トーチ制御（issue #10）: 実機のカメラハードウェアに依存するため androidTest で検証する
    // 例外（docs/03-native.md 9 節）。
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
