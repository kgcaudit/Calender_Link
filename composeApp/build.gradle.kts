import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // Declared now so the shared code stays iOS-clean; these only build on macOS
    // (Xcode). On Linux they are skipped (kotlin.native.ignoreDisabledTargets).
    // JVM target exists only to render screens headlessly in tests (no emulator
    // is available on the build host); it is not shipped.
    jvm("desktop")

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation("org.jetbrains.compose.ui:ui-backhandler:1.9.0")
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.androidx.lifecycle.runtime.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.work.runtime)
            implementation(libs.androidx.glance.appwidget)
        }
    }
}

android {
    namespace = "app.olo.calendar"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.olo.calendar"
        minSdk = 26
        targetSdk = 36
        versionCode = 46
        versionName = "0.15.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key until a release keystore exists, so the
            // APK installs directly for on-device testing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
