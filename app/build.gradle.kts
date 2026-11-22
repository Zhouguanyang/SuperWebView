import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use { load(it) }
    }
}

fun signingProp(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty(name)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.sevpor.chat"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.sevpor.chat"
        minSdk = 28
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.1"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    val releaseStoreFilePath = signingProp("RELEASE_STORE_FILE")
    val releaseStorePassword = signingProp("RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = signingProp("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = signingProp("RELEASE_KEY_PASSWORD")
    val hasReleaseSigning =
        !releaseStoreFilePath.isNullOrBlank() &&
            !releaseStorePassword.isNullOrBlank() &&
            !releaseKeyAlias.isNullOrBlank() &&
            !releaseKeyPassword.isNullOrBlank() &&
            file(releaseStoreFilePath).isFile

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols.add("**/libandroidx.graphics.path.so")
        }
    }
}

androidComponents {
    beforeVariants(selector().all()) { variantBuilder ->
        (variantBuilder as? com.android.build.api.variant.HasDeviceTestsBuilder)
            ?.deviceTests
            ?.get(com.android.build.api.variant.DeviceTestBuilder.ANDROID_TEST_TYPE)
            ?.enable = false
        (variantBuilder as? com.android.build.api.variant.HasHostTestsBuilder)
            ?.hostTests
            ?.get(com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE)
            ?.enable = false
    }
}

dependencies {
    // Compose Bill of Materials
    implementation(platform(libs.androidx.compose.bom))
    // Compose dependencies
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    // Core
    implementation(libs.androidx.core.ktx)
    // GeckoView
    implementation(libs.mozilla.geckoview)
    // SplashScreen
    implementation(libs.androidx.core.splashscreen)
}
