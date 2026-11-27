import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val releaseStoreFilePath = keystoreProperties.getProperty("storeFile")
    ?: System.getenv("RELEASE_STORE_FILE")
val hasReleaseSigning = !releaseStoreFilePath.isNullOrBlank() &&
    rootProject.file(releaseStoreFilePath).exists()

android {
    namespace = "com.quandot.musicplayer"
    compileSdk = 35
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.quandot.playdepth.music"
        minSdk = 26
        targetSdk = 35
        versionCode = 115
        versionName = "1.1.0-beta5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = keystoreProperties.getProperty("storePassword")
                    ?: System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                    ?: System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                    ?: System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
        }
        release {
            isDebuggable = false
            isJniDebuggable = false
            isPseudoLocalesEnabled = false
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo {
                include = false
            }
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose BOM & Material 3
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation & Image Loading
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Media3 (ExoPlayer + MediaSession + FFmpeg SW Decoder)
    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-session:1.5.0")
    implementation("androidx.media3:media3-ui:1.5.0")
    implementation("androidx.media3:media3-common:1.5.0")
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

    // ART Baseline Profile installer for faster cold-start & smoother Compose frames
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Reorderable LazyColumn (Calvin-LL/Reorderable)
    implementation("sh.calvin.reorderable:reorderable:2.4.3")

    // FastScroller for Compose Multiplatform (oikvpqya/fastscroller-compose-multiplatform)
    implementation("io.github.oikvpqya.compose.fastscroller:fastscroller-core:0.3.2")
    implementation("io.github.oikvpqya.compose.fastscroller:fastscroller-material3:0.3.2")
    implementation("io.github.oikvpqya.compose.fastscroller:fastscroller-indicator:0.3.2")

    // Unit testing
    testImplementation("junit:junit:4.13.2")
}
