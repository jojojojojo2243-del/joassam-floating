plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.joassam.floating"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.joassam.floating"
        minSdk = 26
        targetSdk = 34
        // 자동 빌드 번호(깃허브 Actions 실행 번호)를 버전으로 사용 → 새 빌드가 항상 더 높은 버전이라 자동 업데이트가 가능
        val build = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 6
        versionCode = build
        versionName = "1.6 (빌드 $build)"
    }

    // 항상 같은 서명 → 새 버전을 덮어서 설치 가능 (매번 지우고 다시 깔 필요 없음)
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
}
