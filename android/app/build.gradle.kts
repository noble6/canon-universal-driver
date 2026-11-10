plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.noble6.canonivec"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.noble6.canonivec"
        minSdk = 28
        targetSdk = 36
        versionCode = 10900
        versionName = "1.9.0"
    }

    // release key from the environment (CI secrets, or a local keystore)
    val keystore = System.getenv("IVEC_KEYSTORE")
    if (keystore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("IVEC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("IVEC_KEY_ALIAS") ?: "ivecprint"
                keyPassword = System.getenv("IVEC_KEYSTORE_PASSWORD")
            }
        }
    }

    sourceSets {
        // the model database is shared with the Linux driver
        getByName("main").assets.directories.add("../../models")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
}
