plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.airlur.airmouse"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.airlur.airmouse"
        minSdk = 28
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

// 本项目刻意不引入任何第三方依赖（不用 AndroidX / AppCompat），
// 全部使用平台自带 API，构建速度快、失败面小。
dependencies {
}
