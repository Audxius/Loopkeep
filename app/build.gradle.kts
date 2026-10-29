plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release versions come from the git tag via -PappVersionName / -PappVersionCode (see .github/workflows/release.yml).
val appVersionName = providers.gradleProperty("appVersionName").getOrElse("0.0.0-dev")
val appVersionCode = providers.gradleProperty("appVersionCode").getOrElse("1").toInt()

// Release signing is read from the environment so no secret ever lives in the repo.
val releaseStorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")
val releaseStorePassword: String? = System.getenv("RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }
val requireReleaseSigning = providers.gradleProperty("requireReleaseSigning").getOrElse("false").toBoolean()

if (requireReleaseSigning && !hasReleaseSigning) {
    throw GradleException(
        "Release signing requested but RELEASE_KEYSTORE_PATH / RELEASE_KEYSTORE_PASSWORD / " +
            "RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD are not all set. See SETUP.md."
    )
}

android {
    namespace = "io.github.audxius.loopkeep"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.audxius.loopkeep"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        ndk {
            // yt-dlp ships a full Python runtime per ABI; keep the single APK small enough.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // youtubedl-android unpacks its Python/yt-dlp zips from the native library dir.
        jniLibs.useLegacyPackaging = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.youtubedl.library)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
