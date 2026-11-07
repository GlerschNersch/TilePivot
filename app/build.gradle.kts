plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.x360reborn"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.tilepivot"
        minSdk = 33
        targetSdk = 36
        versionCode = 23
        versionName = "0.10.2"
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
            // R8 + resource shrinking. The app is plain Kotlin custom Views with no reflection or
            // AndroidX, so no keep rules are needed (proguard-rules.pro stays empty unless a
            // smoke test shows otherwise).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Optional, PRIVATE avatar-pack decoder: a sibling folder that is never part of this repo, compiled
    // in only when a build is run with -PwithAvatarPack (sideloaded personal builds). Without it the
    // app's AvatarPacks.decoder is null and the import option never appears. A Play bundle must never
    // contain it, so bundle tasks refuse to run with the flag.
    val avatarPackDir = rootProject.file("../TilePivotAvatarPack")
    if (providers.gradleProperty("withAvatarPack").isPresent) {
        require(avatarPackDir.isDirectory) { "withAvatarPack set but ${avatarPackDir} is missing" }
        require(gradle.startParameter.taskNames.none { it.contains("bundle", ignoreCase = true) }) {
            "withAvatarPack must not be used for Play bundles"
        }
        sourceSets["main"].java.srcDir(File(avatarPackDir, "src/main/java"))
        sourceSets["test"].java.srcDir(File(avatarPackDir, "src/test/java"))
        buildTypes.getByName("release").proguardFile(File(avatarPackDir, "proguard-rules.pro"))
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
