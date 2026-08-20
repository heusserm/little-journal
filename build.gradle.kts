// Declare plugin versions once for the whole build; modules apply them without versions.
plugins {
    kotlin("multiplatform") version "2.2.20" apply false
    kotlin("plugin.serialization") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.compose") version "1.9.0" apply false
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("app.cash.sqldelight") version "2.1.0" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.1" apply false
}

// ---- versioning -------------------------------------------------------------
//
// One version, in gradle.properties, reaching three platforms that each want it
// in a different shape.

/** The version as written: semantic, and what users are told. */
val appVersion: String = providers.gradleProperty("littlejournal.version").get()

/**
 * A monotonic integer for Android's versionCode, which cannot be a string and
 * must never go backwards. 0.1.0 -> 100, 1.2.3 -> 10203.
 */
val appVersionCode: Int = appVersion.split(".").let { (major, minor, patch) ->
    major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
}

/**
 * The same version, made legal for a macOS package.
 *
 * jpackage rejects a major of 0 outright -- Compose Desktop reports
 * "Illegal version for 'Dmg': '0.1.0' is not a valid version" and the build
 * stops. So a 0.x version is carried into the 1.x range by shifting, which
 * keeps ordering intact and cannot collide: 0.1.0 -> 1.1.0, 0.2.0 -> 1.2.0.
 * Once the real major reaches 1 this returns the version untouched.
 *
 * This is the DMG's *internal* version only. The artifact is renamed to carry
 * the true version by the `dmg` lane, so nobody reads 1.1.0 and believes it.
 */
val macPackageVersion: String = appVersion.split(".").let { (major, minor, patch) ->
    if (major.toInt() == 0) "1.$minor.$patch" else appVersion
}

extra["appVersion"] = appVersion
extra["appVersionCode"] = appVersionCode
extra["macPackageVersion"] = macPackageVersion
