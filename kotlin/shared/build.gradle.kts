plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(17)
    jvm()
    // AGP 8.11 uses androidLibrary; android{} starts with AGP 8.12.
    androidLibrary {
        namespace = "org.openwrench.core"
        compileSdk = 35
        minSdk = 24
        withHostTestBuilder {}.configure {}
    }
    sourceSets {
        jvmTest {
            resources.srcDir(rootProject.layout.projectDirectory.dir("../fixtures"))
            dependencies {
                implementation(libs.kotlinx.serialization.json)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
