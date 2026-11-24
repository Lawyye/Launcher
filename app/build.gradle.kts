plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.lawyye.wired"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lawyye.wired"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        create("wired") {
            // Personal signing key, kept in the repo so every CI build can update the previous one.
            storeFile = file("wired.keystore")
            storePassword = "wiredlayer07"
            keyAlias = "wired"
            keyPassword = "wiredlayer07"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("wired")
        }
        debug {
            signingConfig = signingConfigs.getByName("wired")
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
        buildConfig = true
    }

    testOptions {
        // JVM unit tests (app/src/test) never touch the real Android; stubs answer with defaults
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // CI runs lintRelease and keeps the HTML report as a build artifact (see build.yml);
        // the release itself is not blocked by lint yet
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // tests only — nothing of this goes into the APK, which still uses only the Android framework
    testImplementation("junit:junit:4.13.2")
}
