plugins {
    alias(libs.plugins.android.application)
    // Compose compiler plugin; its version ref pins Kotlin 2.4.10 (built-in Kotlin: no kotlin-android plugin).
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.yangtse.hearwrite"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.yangtse.hearwrite"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // compounds.json fixtures parse through the same domain function as the app.
    testImplementation(libs.kotlinx.serialization.json)
}
