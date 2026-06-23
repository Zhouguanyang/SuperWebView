import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.sevpor.chat"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sevpor.chat"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    // Webkit
    implementation(libs.androidx.webkit)
    // SplashScreen
    implementation(libs.androidx.core.splashscreen)
    // Swiperefreshlayout
    implementation(libs.androidx.swiperefreshlayout)
}
