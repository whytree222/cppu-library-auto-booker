import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun readProperties(name: String): Properties {
    val file = rootProject.file(name)
    require(file.isFile) { "$name is required at the repository root" }
    return Properties().apply { file.inputStream().use { load(it) } }
}

fun quoted(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val versionProps = readProperties("version.properties")
val appVersionCode = versionProps.getProperty("versionCode")?.toIntOrNull()
    ?: error("version.properties: versionCode is required")
val appVersionName = versionProps.getProperty("versionName")
    ?: error("version.properties: versionName is required")
val minSupportedVersionCode = versionProps.getProperty("minSupportedVersionCode")?.toIntOrNull()
    ?: appVersionCode
require(appVersionCode > 0) { "version.properties: versionCode must be positive" }
require(Regex("^\\d+\\.\\d+\\.\\d+$").matches(appVersionName)) {
    "version.properties: versionName must look like 1.2.3"
}
require(minSupportedVersionCode in 1..appVersionCode) {
    "version.properties: minSupportedVersionCode must be between 1 and versionCode"
}

val distribution = readProperties("distribution.properties")
val giteeOwner = distribution.getProperty("giteeOwner").orEmpty().trim()
val giteeRepo = distribution.getProperty("giteeRepo").orEmpty().trim()
val giteeBranch = distribution.getProperty("giteeBranch", "main").trim()
val githubRepo = distribution.getProperty("githubRepo").orEmpty().trim()
val updateManifestUrl = if (giteeOwner.isEmpty()) "" else
    "https://gitee.com/$giteeOwner/$giteeRepo/raw/$giteeBranch/update.json"
val updateFallbackUrl = if (giteeOwner.isEmpty() || githubRepo.isEmpty()) "" else
    "https://github.com/$githubRepo/releases/latest/download/update.json"
val updatePageUrl = if (giteeOwner.isEmpty()) "" else
    "https://gitee.com/$giteeOwner/$giteeRepo/releases/latest"

val envKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val envKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val envKeyAlias = System.getenv("ANDROID_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val envKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val signingValues = listOf(envKeystorePath, envKeystorePassword, envKeyAlias, envKeyPassword)
val signingConfigured = signingValues.all { it != null }
require(signingConfigured || signingValues.all { it == null }) {
    "Set all of ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD, or none of them"
}

android {
    namespace = "cn.edu.cppu.libraryautobooker"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.edu.cppu.libraryautobooker"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "UPDATE_MANIFEST_URL", quoted(updateManifestUrl))
        buildConfigField("String", "UPDATE_MANIFEST_FALLBACK_URL", quoted(updateFallbackUrl))
        buildConfigField("String", "UPDATE_PAGE_URL", quoted(updatePageUrl))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (signingConfigured) {
            create("release") {
                storeFile = file(requireNotNull(envKeystorePath))
                storePassword = envKeystorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Ship only phone ABIs; x86/x86_64 ONNX runtimes are for local emulators (debug).
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            // Debug builds use a different applicationId and cannot install release APKs.
            buildConfigField("String", "UPDATE_MANIFEST_URL", "\"\"")
            buildConfigField("String", "UPDATE_MANIFEST_FALLBACK_URL", "\"\"")
            buildConfigField("String", "UPDATE_PAGE_URL", "\"\"")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation("org.jsoup:jsoup:1.23.2")
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    testImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// CI and local builds obtain the pinned public ddddocr assets with scripts/prepare_ocr.py.
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
