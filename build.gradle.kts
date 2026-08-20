// Declare plugin versions once for the whole build; modules apply them without versions.
plugins {
    kotlin("multiplatform") version "2.2.20" apply false
    kotlin("plugin.serialization") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.compose") version "1.9.0" apply false
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("app.cash.sqldelight") version "2.1.0" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.1" apply false
}
