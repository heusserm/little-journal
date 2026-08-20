import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlinx.kover")
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    androidTarget()
    jvm("desktop")
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":storage"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.9.3")
        }
        val desktopMain by getting
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
        }
        val desktopTest by getting
        desktopTest.dependencies {
            // The UI test harness needs a real toolkit to render into.
            implementation(compose.desktop.currentOs)
        }
    }
}

android {
    namespace = "com.xndev.littlejournal"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.xndev.littlejournal"
        minSdk = 26
        targetSdk = 35
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersion"] as String
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application {
        mainClass = "com.xndev.littlejournal.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "LittleJournal"
            // Not appVersion: macOS rejects a major of 0. See macPackageVersion
            // in the root build for why this is shifted rather than faked.
            packageVersion = rootProject.extra["macPackageVersion"] as String
        }
    }
}

// Desktop run tasks execute from the repo root so the dev database lands in
// the project directory rather than wherever Gradle happens to be.
tasks.withType<JavaExec>().configureEach {
    workingDir = rootProject.projectDir
}

// Generated SQLDelight code is not ours to test; counting it flatters the
// storage number and hides the UI having no coverage at all.
kover {
    reports {
        filters {
            excludes {
                packages("com.xndev.littlejournal.db")
            }
        }
    }
}
