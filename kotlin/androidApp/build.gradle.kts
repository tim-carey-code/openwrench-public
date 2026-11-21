plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}
kotlin { jvmToolchain(17) }
// Explicit review/update modes; default CI behavior stays unchanged.
val unsignedBuild = providers.gradleProperty("openwrenchUnsigned").orNull.let { value ->
    require(value == null || value == "true" || value == "false") { "openwrenchUnsigned must be true or false" }
    value == "true"
}
val requireRetainedKey = providers.gradleProperty("openwrenchRequireRetainedKey").orNull.let { value ->
    require(value == null || value == "true" || value == "false") { "openwrenchRequireRetainedKey must be true or false" }
    value == "true"
}
android {
    namespace = "org.openwrench.frontend.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.openwrench.android"
        minSdk = 24
        targetSdk = 35
        versionCode = 7
        versionName = "0.3.5-native-preview"
    }
    buildFeatures { compose = true }
    buildTypes {
        // Separate sandbox for UI review when the installed app's signing key is unavailable.
        // This cannot replace the regular app or read its private history.
        // Distinct side-by-side app; never replaces Telemetry Preview or shares its history.
        // Isolated preview after workspace reset; original Toolbar Preview identity is unchanged.
        // Separate review app when the earlier preview signing key is unavailable.
        // Authorized separate M1/M2 test app. Its signing key is supplied outside the repository.
        create("workspacePreview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".workspacepreview"
            versionNameSuffix = "-workspace-preview"
            matchingFallbacks += "debug"
        }
        create("liveHomePreview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".livehomepreview"
            versionNameSuffix = "-live-home-preview"
            matchingFallbacks += "debug"
        }
        create("replayStatusPreview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".replaystatuspreview"
            versionNameSuffix = "-replay-status-preview"
            matchingFallbacks += "debug"
        }
        create("toolbarPreview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".toolbarpreview"
            versionNameSuffix = "-toolbar-preview"
            matchingFallbacks += "debug"
        }
        create("telemetryPreview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".telemetrypreview"
            versionNameSuffix = "-telemetry-preview"
            matchingFallbacks += "debug"
        }
        create("chartPreview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".chartpreview"
            versionNameSuffix = "-chart-preview"
            matchingFallbacks += "debug"
        }
        create("graphPreview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".graphpreview"
            versionNameSuffix = "-graph-preview"
            matchingFallbacks += "debug"
        }
        create("usbPreview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".usbpreview"
            versionNameSuffix = "-live-fuel-trims"
            matchingFallbacks += "debug"
        }
    }
    if (unsignedBuild) buildTypes.configureEach { signingConfig = null }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":shared"))
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    // Native USB serial drivers (CDC ACM, FTDI, CP210x, CH34x, PL2303).
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.commonmark)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
}

// Opt in for intended updates: stop before AGP can generate a missing default key.
// Existence is not certificate identity; the separate APK preflight remains required.
if (requireRetainedKey) {
    tasks.matching { it.name.startsWith("validateSigning") }.configureEach {
        doFirst {
            val buildTypeName = name.removePrefix("validateSigning").replaceFirstChar { it.lowercaseChar() }
            val signing = android.buildTypes.findByName(buildTypeName)?.signingConfig
            check(signing?.storeFile?.isFile == true) {
                "The selected signing key is absent. Recover the original owner-managed key, " +
                    "or use -PopenwrenchUnsigned=true for non-installable review output. Do not create a replacement key to update an installed app."
            }
        }
    }
}

// Only the authorized M1/M2 preview advances; every existing application variant keeps version 7.
androidComponents {
    onVariants(selector().withBuildType("workspacePreview")) { variant ->
        variant.outputs.forEach { it.versionCode.set(10) }
    }
}
