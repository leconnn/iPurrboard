plugins {
    id("com.android.application")
}

// Release signing reads ../keystore.properties (gitignored: storeFile, storePassword, keyAlias,
// keyPassword). Without it, release builds are unsigned.
val keystore: Map<String, String>? = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.readLines()
    ?.filter { '=' in it && !it.trimStart().startsWith("#") }
    ?.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

android {
    namespace = "dev.leconnn.ioskeyboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.leconnn.ioskeyboard"
        minSdk = 26
        targetSdk = 36
        // Every release bumps both (the in-app updater compares versionName with GitHub releases).
        versionCode = 2
        versionName = "0.2"
        // GIF search is off in this app (no key is included).
        buildConfigField("String", "GIPHY_API_KEY", "\"\"")
        buildConfigField("boolean", "GIFS", "false")
        // Software Update: the GitHub repository whose releases this build updates from.
        buildConfigField("String", "UPDATE_REPO", "\"leconnn/iPurrboard\"")
        buildConfigField("boolean", "UPDATE_PRIVATE", "false")
        buildConfigField("String", "UPDATE_TOKEN", "\"\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore.getValue("storeFile"))
            storePassword = keystore.getValue("storePassword")
            keyAlias = keystore.getValue("keyAlias")
            keyPassword = keystore.getValue("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    // Memory-mapped .bin assets must stay uncompressed in the APK.
    androidResources {
        noCompress += "bin"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// APKs are named for the app and version: iPurrboard-0.1-release.apk.
base {
    archivesName = "iPurrboard-" + android.defaultConfig.versionName
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Optional local build settings, not part of this repository.
rootProject.file("local.gradle").takeIf { it.exists() }?.let { apply(from = it) }
