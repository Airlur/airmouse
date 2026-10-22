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
        versionCode = 7
        versionName = "3.4"
    }

    /**
     * 固定签名。
     *
     * 默认的 debug 签名用的是 ~/.android/debug.keystore，文件不存在时构建工具会
     * 自动随机生成一份。CI 每次都是全新虚拟机，于是每次构建签名都不同，
     * 安卓会以「签名不一致」拒绝覆盖安装，只能先卸载。
     *
     * 所以这里把 keystore 固化进仓库，保证每次构建签名一致。
     * 用的是标准 debug 密钥（androiddebugkey / 密码 android），属于公开信息，
     * 与每台开发机自动生成的那份等价，放进公开仓库没有额外风险。
     */
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 与 debug 用同一把密钥，保证升级覆盖时签名一致；
            // 这样发布出去的是正式构建（非 debuggable），而不是 debug 包。
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
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

// 本项目刻意不引入任何第三方依赖（不用 AndroidX / AppCompat），
// 全部使用平台自带 API，构建速度快、失败面小。
dependencies {
}
