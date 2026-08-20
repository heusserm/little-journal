plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("app.cash.sqldelight")
}

val sqldelight = "2.1.0"

kotlin {
    androidTarget()
    jvm()
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Storage"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("app.cash.sqldelight:runtime:$sqldelight")
            implementation("app.cash.sqldelight:coroutines-extensions:$sqldelight")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation("app.cash.sqldelight:sqlite-driver:$sqldelight")
        }
        androidMain.dependencies {
            implementation("app.cash.sqldelight:android-driver:$sqldelight")
        }
        iosMain.dependencies {
            implementation("app.cash.sqldelight:native-driver:$sqldelight")
        }
    }
}

sqldelight {
    databases {
        create("JournalDatabase") {
            packageName.set("com.xndev.littlejournal.db")
        }
    }
}

android {
    namespace = "com.xndev.littlejournal.storage"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
