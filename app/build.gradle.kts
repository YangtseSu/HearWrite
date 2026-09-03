import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // Compose compiler plugin; its version ref pins Kotlin 2.4.10 (built-in Kotlin: no kotlin-android plugin).
    alias(libs.plugins.kotlin.compose)
    // Room annotation processing (AGP 9 built-in Kotlin requires KSP >= 2.3.6).
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.yangtse.hearwrite"
    compileSdk = 37

defaultConfig {
        applicationId = "org.yangtse.hearwrite"
        minSdk = 36
        targetSdk = 37
        // Version scheme: versionName = MAJOR.MINOR.PATCH (semver; 0.x.y while
        // pre-release). versionCode = a monotonic integer, +1 per signed
        // release artifact, never reused or re-ordered. First signed release:
        // 1 / "0.1.0" (Phase 10).
        versionCode = 1
        versionName = "0.1.0"
    }

buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signing comes from the gitignored keystore.properties at the repo
            // root (template: keystore.properties.example). Without that file
            // the release build stays unsigned — installs are then impossible,
            // by design: release artifacts must be signed with the release key.
            val keystorePropsFile = rootProject.file("keystore.properties")
            if (keystorePropsFile.exists()) {
                val keystoreProps = Properties().apply {
                    keystorePropsFile.inputStream().use { load(it) }
                }
                signingConfig = signingConfigs.create("release") {
                    storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                    storePassword = keystoreProps.getProperty("storePassword")
                    keyAlias = keystoreProps.getProperty("keyAlias")
                    keyPassword = keystoreProps.getProperty("keyPassword")
                }
            }
        }
    }

    // AGENTS.md: toolchain language level 21. Built-in Kotlin derives jvmTarget from this.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    // AGENTS.md: repo-root data/ ships verbatim as APK assets; meta/ is regeneration
    // source only and must not be packaged (ignore patterns are aapt globs).
    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~:meta"
    }
    sourceSets["main"].assets.srcDir(rootProject.file("data"))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Recorded in the catalog at scaffold time; JSON parsing for config/fixtures.
    implementation(libs.kotlinx.serialization.json)

    // Room persistence (wrong words / history / favorites).
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Youdao dict-voice downloads (TTS priority chain).
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // compounds.json fixtures parse through the same domain function as the app.
    testImplementation(libs.kotlinx.serialization.json)
}
