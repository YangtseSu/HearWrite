// Root build file: declares plugin versions once, applied in modules.
// AGP 9 has built-in Kotlin support — never apply org.jetbrains.kotlin.android.
// The Kotlin version (catalog `kotlin`) comes via the Compose compiler plugin,
// whose version drags the matching Kotlin Gradle Plugin. Tracks latest stable.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
