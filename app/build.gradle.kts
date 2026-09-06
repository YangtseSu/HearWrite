import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // Compose compiler plugin; its version ref pins Kotlin 2.4.10 (built-in Kotlin: no kotlin-android plugin).
    alias(libs.plugins.kotlin.compose)
    // Room annotation processing (AGP 9 built-in Kotlin requires KSP >= 2.3.6).
    alias(libs.plugins.ksp)
}

// Captured during configuration (readable here, unlike at task-graph time under
// configuration cache) — versionName stays authoritative in defaultConfig above.
lateinit var releaseVersionName: String

android {
    namespace = "org.yangtse.hearwrite"
    compileSdk = 37

    // Release-certificate signing (gitignored keystore.properties at the repo
    // root). Applied to the release buildType and — when the file exists — to
    // debug builds too: one signature across local debug installs and signed
    // releases lets `adb install -r` upgrade either direction without an
    // uninstall (which would wipe Room/DataStore data). Missing file → debug
    // falls back to the default debug key, release stays unsigned (by design).
    val keystorePropsFile = rootProject.file("keystore.properties")
    if (keystorePropsFile.exists()) {
        val keystoreProps = Properties().apply {
            keystorePropsFile.inputStream().use { load(it) }
        }
        signingConfigs.create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    defaultConfig {
        applicationId = "org.yangtse.hearwrite"
        minSdk = 36
        targetSdk = 37
        // Version scheme: versionName = MAJOR.MINOR.PATCH (semver; 0.x.y while
        // pre-release). versionCode = a monotonic integer, +1 per signed
        // release artifact, never reused or re-ordered. First signed release:
        // 1 / "0.1.0" (Phase 10).
        versionCode = 4
        versionName = "0.3.1"
    }

    // versionName is read once here (configuration phase) so the
    // packageVersionedRelease copy task below can name its outputs without
    // touching the android extension at task-graph time.
    afterEvaluate {
        releaseVersionName = defaultConfig.versionName
            ?: error("defaultConfig.versionName must be set before packaging a release")
    }

    buildTypes {
        debug {
            if (signingConfigs.any { it.name == "release" }) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingConfigs.any { it.name == "release" }) {
                signingConfig = signingConfigs.getByName("release")
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

}

// Versioned release artifacts for distribution/archiving (DEVELOPMENT.md §4):
// the signed APK and its R8 mapping get convention-named copies
// (HearWrite-<versionName>.apk / HearWrite-<versionName>-mapping.txt) while AGP's
// default output file name stays in place — Studio and the signature-verify
// step depend on it. CI runs this task after :app:assembleRelease
// (.github/workflows/release.yml); the copy task only packages the release
// buildType, so normal builds are untouched.
tasks.register<Copy>("packageVersionedRelease") {
    dependsOn("assembleRelease")
    val base = "HearWrite-$releaseVersionName"
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("app-release.apk")
        rename { _ -> "$base.apk" }
    }
    from(layout.buildDirectory.dir("outputs/mapping/release")) {
        include("mapping.txt")
        rename { _ -> "$base-mapping.txt" }
    }
    // Separate output dir: AGP owns build/outputs/apk/release (its
    // output-metadata.json lives there) — writing copies into it would trip
    // Gradle's task-output-overlap validation.
    into(layout.buildDirectory.dir("dist"))
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

    // EXIF orientation for the OCR crop decode (androidx exifinterface).
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // compounds.json fixtures parse through the same domain function as the app.
    testImplementation(libs.kotlinx.serialization.json)
}
