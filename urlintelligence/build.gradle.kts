// Standalone, platform-agnostic module: "Property URL Intelligence".
//
// Hard rules enforced by this module (see also docs/PROPERTY_URL_INTELLIGENCE.md):
//   * No Android dependencies (no android.* / androidx.* / Room / OkHttp).
//   * No secrets, no API keys, no credentials, no signing material.
//   * Pure JVM/Kotlin so every parser, the state machine, retry and idempotency
//     logic are unit-testable without an emulator or Robolectric.
//
// The Android app wires this module in through thin adapters that live in
// `com.example.data.urlintelligence` inside :app.
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
  implementation(libs.kotlinx.coroutines.core)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}
