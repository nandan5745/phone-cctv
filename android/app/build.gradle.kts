plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing values come from environment variables set by the GitHub workflow.
// The keystore itself is a GitHub secret, never stored in the repo.
val ksPath: String? = System.getenv("CCTV_KEYSTORE_PATH")

android {
    namespace = "com.nandan.cctv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nandan.cctv"
        minSdk = 21
        // Target Android 5.1 on purpose: the phone runs 5.1, and this keeps
        // install-time permissions (no runtime permission prompts needed).
        targetSdk = 22
        // Every CI build gets a higher number, so updates install over the old app.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.4.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        ndk { abiFilters += "armeabi-v7a" }
    }

    signingConfigs {
        if (ksPath != null) {
            create("fixed") {
                storeFile = file(ksPath)
                storePassword = System.getenv("CCTV_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CCTV_KEY_ALIAS")
                keyPassword = System.getenv("CCTV_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (ksPath != null) signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }

    lint {
        // targetSdk 22 is deliberate (see above); don't fail the build over it.
        disable += "ExpiredTargetSdkVersion"
        checkReleaseBuilds = false
    }
}
