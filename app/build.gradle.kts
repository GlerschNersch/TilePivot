plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.x360reborn"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.x360reborn"
        minSdk = 33
        targetSdk = 36
        versionCode = 13
        versionName = "0.5.4"
    }

    // Release signing comes from ~/.gradle/gradle.properties (never the repo): REBORN_KEYSTORE,
    // REBORN_KEYSTORE_PASSWORD, REBORN_KEY_ALIAS, REBORN_KEY_PASSWORD. Without them, release builds
    // fall back to the debug key so anyone can still build and sideload.
    val keystore = providers.gradleProperty("REBORN_KEYSTORE").orNull
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = providers.gradleProperty("REBORN_KEYSTORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("REBORN_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("REBORN_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // The real org.json for JVM tests (android.jar only has stubs); Backup uses it.
    testImplementation("org.json:json:20240303")
}
