import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing secrets come from keystore.properties (local) or environment variables (CI).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(prop: String, env: String): String? = System.getenv(env) ?: keystoreProps.getProperty(prop)

val buildNumber = (System.getenv("VERSION_CODE") ?: project.findProperty("versionCode")?.toString() ?: "1").toInt()
val updateUrl = System.getenv("UPDATE_URL") ?: project.findProperty("updateUrl")?.toString() ?: ""

android {
    namespace = "com.homebase.tvlauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.homebase.tvlauncher"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/release.jks")
            storePassword = secret("storePassword", "KEYSTORE_PASSWORD")
            keyAlias = secret("keyAlias", "KEY_ALIAS")
            keyPassword = secret("keyPassword", "KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Debug builds also use the release key so you can switch between them
            // on the TV without uninstalling (which would lose your pinned apps).
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
