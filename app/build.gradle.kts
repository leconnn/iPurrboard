import java.util.zip.ZipFile

plugins {
    id("com.android.application")
}

// Release signing reads ../keystore.properties (gitignored: storeFile, storePassword, keyAlias,
// keyPassword). Without it, release builds are unsigned.
val keystore: Map<String, String>? = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.readLines()
    ?.filter { '=' in it && !it.trimStart().startsWith("#") }
    ?.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

// GIF search key: `giphy.apiKey=...` in ../local.properties (gitignored, never committed).
val giphyKey: String = rootProject.file("local.properties").takeIf { it.exists() }
    ?.readLines()
    ?.firstOrNull { it.trimStart().startsWith("giphy.apiKey=") }
    ?.substringAfter('=')?.trim().orEmpty()

android {
    namespace = "dev.leconnn.ioskeyboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.leconnn.ioskeyboard"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    // personal: everything, including the personal-use Apple emoji and key sounds (only in
    // src/personal, gitignored) and GIFs with the GIPHY key. public: shareable, so none of those.
    flavorDimensions += "dist"
    productFlavors {
        create("personal") {
            dimension = "dist"
            buildConfigField("String", "GIPHY_API_KEY", "\"$giphyKey\"")
            buildConfigField("boolean", "GIFS", "true")
        }
        create("public") {
            dimension = "dist"
            buildConfigField("String", "GIPHY_API_KEY", "\"\"")
            buildConfigField("boolean", "GIFS", "false")
        }
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

    // apple_emoji.bin is memory-mapped, so it must stay uncompressed in the APK.
    androidResources {
        noCompress += "bin"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// APKs are named for what they are: iPurrboard-0.1-personal-release.apk, iPurrboard-0.1-public-release.apk.
base {
    archivesName = "iPurrboard-" + android.defaultConfig.versionName
}

// A public APK must never carry the personal-use Apple files.
tasks.matching { it.name == "assemblePublicRelease" || it.name == "assemblePublicDebug" }.configureEach {
    doLast {
        val apks = fileTree(layout.buildDirectory.dir("outputs/apk/public")) { include("**/*.apk") }
        apks.forEach { apk ->
            val names = ZipFile(apk).use { z -> z.entries().toList().map { e -> e.name } }
            // Resource files get shortened names in release APKs, so look for any sound file too
            // (the app has no sounds of its own: without the personal ones it uses Android's).
            val bad = names.filter { "apple_emoji" in it || it.endsWith(".wav") }
            if (bad.isNotEmpty()) throw GradleException("${apk.name} contains personal-use files: $bad")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
