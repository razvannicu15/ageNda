plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val vCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "ro.noraz.nagenda"
    compileSdk = 35

    defaultConfig {
        applicationId = "ro.noraz.nagenda"
        minSdk = 26
        targetSdk = 35
        versionCode = vCode
        versionName = "1.0.$vCode"
    }

    signingConfigs {
        create("release") {
            storeFile = file("nagenda.jks")
            storePassword = "noraznagenda"
            keyAlias = "nagenda"
            keyPassword = "noraznagenda"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
