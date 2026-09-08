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

        // Required Android ABI families (spec §10). Chaquopy bundles a
        // matching native CPython runtime + stdlib for each of these.
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
        // Chaquopy's own native libs can trigger duplicate-META-INF
        // warnings from transitive deps; keep the build clean.
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Phase 1 does not yet execute Python (that's Phase 2), but the plugin
// must be configured now since it drives ABI packaging for the runtime
// that Phase 2 will use — configuring it later would change how the APK
// is built, which we want pinned from the start.
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

    // Code editor core + TextMate-based syntax highlighting.
    implementation(platform("io.github.Rosemoe.sora-editor:bom:0.23.6"))
    implementation("io.github.Rosemoe.sora-editor:editor")
    implementation("io.github.Rosemoe.sora-editor:language-textmate")
}