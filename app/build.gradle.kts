plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.example.speech_to_text"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.speech_to_text"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
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
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs += ("-Xextended-compiler-checks") // Bu satırı ekleyin

    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"

            pickFirsts += listOf( // pickFirst yerine pickFirsts ve liste
                "lib/x86/libvosk.so",
                "lib/x86_64/libvosk.so",
                "lib/armeabi-v7a/libvosk.so",
                "lib/arm64-v8a/libvosk.so"
            )





        }
    }

}

dependencies {
    // En son sürümü kullanır (+)
    implementation(libs.vosk.android.v0332)
    


    implementation(platform(libs.androidx.compose.bom.v20240201)) // BOM'u ekle. Güncel sürüm!


    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview) //Bunu ekleme nedenin preview içindi.
    implementation(libs.material3) // Sadece material3

    implementation(libs.androidx.lifecycle.runtime.ktx.v270) // Güncel
    implementation(libs.androidx.activity.compose.v182) // Güncel
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)


}

configurations.all {
    resolutionStrategy {
        force("net.java.dev.jna:jna:4.4.0")
    }
}

