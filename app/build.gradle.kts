plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.pyedit.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pyedit.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Upgraded from 0.23.6 to 0.24.6 specifically for a documented fix:
    // "fix a problem in line width re-calculating when wordwrap is
    // disabled" (0.24.3 changelog) — matches our exact horizontal-scroll
    // gutter bug. Note the Maven group changed at 0.23.7:
    // io.github.Rosemoe.sora-editor -> io.github.rosemoe, and the BOM
    // module was renamed bom -> editor-bom.
    implementation(platform("io.github.rosemoe:editor-bom:0.24.6"))
    implementation("io.github.rosemoe:editor")
}