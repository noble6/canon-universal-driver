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
        versionCode = 1
        versionName = "0.1.0"
    }

    sourceSets {
        // the model database is shared with the Linux driver
        getByName("main").assets.directories.add("../../models")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
