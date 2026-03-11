plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    //alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.voicebot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.voicebot"
        minSdk = 31
        targetSdk = 34
        ndkVersion = "26.1.10909125"
        versionCode = 20251217
        versionName = "1.12.20"
        
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        //compose = true
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf(
                "lib/x86/libonnxruntime.so",
                "lib/x86_64/libonnxruntime.so",
                "lib/armeabi-v7a/libonnxruntime.so",
                "lib/arm64-v8a/libonnxruntime.so"
            )
        }
/*        pickFirst("lib/armeabi-v7a/libonnxruntime.so")
        pickFirst("lib/arm64-v8a/libonnxruntime.so")*/
    }

}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Google AI SDK (Gemini)
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // LiteRT LLM
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.9.0-alpha06") {
        exclude(group = "com.microsoft.onnxruntime")
    }

    // ExecuTorch Android - dùng version mới nhất
    implementation("org.pytorch:executorch-android:1.1.0") {
        exclude(group = "com.microsoft.onnxruntime")
    }

    // Coroutines (bạn đã dùng Flow)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("com.google.ai.edge.localagents:localagents-rag:0.3.0"){
        exclude(group = "com.microsoft.onnxruntime")
    }
    implementation("com.google.mediapipe:tasks-genai:0.10.27"){
        exclude(group = "com.microsoft.onnxruntime")
    }
// Guava (cho ImmutableList, ListenableFuture)
    implementation("com.google.guava:guava:33.3.1-android")

    // Sherpa-ONNX (Piper TTS offline)
    //implementation("com.github.k2-fsa:sherpa-onnx:v1.12.8")

}
