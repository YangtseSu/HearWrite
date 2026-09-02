// Root build file: declares plugin versions once, applied in modules.
// AGP 9 has built-in Kotlin support — never apply org.jetbrains.kotlin.android.
// The pinned Kotlin version (2.4.10) comes via the Compose compiler plugin,
// whose version drags the matching Kotlin Gradle Plugin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
