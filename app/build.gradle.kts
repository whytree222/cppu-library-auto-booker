import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cn.edu.cppu.libraryautobooker"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.edu.cppu.libraryautobooker"
        minSdk = 26
        targetSdk = 35
        versionCode = 30
        versionName = "0.13.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    testImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// CI prepares checksum-pinned assets. Local builds must run scripts/prepare_ocr.py first.
val verifyOcrAssets by tasks.registering {
    doLast {
        val assets = file("src/main/assets/ocr")
        check(file("${assets}/common_old.onnx").isFile && file("${assets}/charsets.json").isFile) {
            "Missing local OCR assets. Run: python scripts/prepare_ocr.py"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(file("${assets}/common_old.onnx").readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(digest == "b8f2ad9cbc1f2e3922a6cb9459e30824e7e2467f3fb4fd61420640e34ea0bf68") {
            "OCR model integrity mismatch. Run: python scripts/prepare_ocr.py"
        }
        val alphabetDigest = MessageDigest.getInstance("SHA-256")
            .digest(file("${assets}/charsets.json").readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(alphabetDigest == "9359f328328c7bc3a1018be2e5f442e8e755d2e92cf91231815768101765b404") {
            "OCR alphabet integrity mismatch. Run: python scripts/prepare_ocr.py"
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyOcrAssets) }
