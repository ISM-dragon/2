// Standalone, platform-agnostic module: "Repair Estimator" (deterministic rehab estimation).
//
// Hard rules enforced by this module (see also docs/repair-estimator.md):
//   * No Android dependencies (no android.* / androidx.* / Room / OkHttp / Retrofit).
//   * No AI, network, database, clock or randomness: same request, same estimate.
//   * Pure JVM/Kotlin so every rule is unit-testable without an emulator or Robolectric.
//
// The Android app does not depend on this module yet. Wiring it into a screen or repository
// is a separate change.
plugins {
  alias(libs.plugins.kotlin.jvm)
  `java-library`
}

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
  compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}

dependencies {
  testImplementation(libs.junit)
}
