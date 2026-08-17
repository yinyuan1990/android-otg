plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.fz.yqlandroid"
    compileSdk = 36

    defaultConfig {
        // ⭐⭐ 2026-08-04 OTG 专版（金凤凰OTG）：applicationId 加 .otg 后缀 = 系统层面的另一个 App，
        //   与主版"金凤凰"可同装一台手机。namespace/代码包名不动，与主仓库 yql-android 代码同构。
        applicationId = "com.fz.yqlandroid.otg"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        // ⭐ 2026-08-03：AGP 8 默认不生成 BuildConfig；OtgLogReporter 自诊断头要读 VERSION_NAME
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    
    // ========== Material Icons Extended ==========
    implementation("androidx.compose.material:material-icons-extended")
    
    // ========== WebRTC (Stream WebRTC Android) ==========
    implementation(libs.stream.webrtc)
    
    // ========== 网络请求 & WebSocket (OkHttp自带WebSocket支持) ==========
    implementation(libs.okhttp)
    
    // ========== JSON解析 ==========
    implementation(libs.gson)
    
    // ========== 协程 ==========
    implementation(libs.kotlinx.coroutines.android)
    
    // ========== Navigation Compose ==========
    implementation(libs.androidx.navigation.compose)
    
    // ========== ViewModel Compose ==========
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    
    // ========== 权限请求 ==========
    implementation(libs.accompanist.permissions)
    
    // ========== CameraX (相机预览) ==========
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    
    // ========== ML Kit 二维码扫描 ==========
    implementation(libs.mlkit.barcode.scanning)

    // ========== 外接OTG摄像头 UVC (第四十八章，AUSBC 社区维护分支，JitPack) ==========
    // ernestp/AndroidUSBCamera 3.5.3：Android 16 + 16KB页对齐已支持（原库 jiangdongguo 停更）
    implementation("com.github.ernestp.AndroidUSBCamera:libausbc:3.5.3")
    // ⚠️ libuvc 必须显式声明：libausbc 的 POM 把它标成 runtime scope（编译期不在 classpath），
    //   而 UvcDeviceMonitor/UvcVideoCapturer 直接用了它里面的 com.jiangdg.usb.USBMonitor /
    //   com.jiangdg.uvc.UVCCamera / com.jiangdg.utils.* → 不加就 "Unresolved reference 'usb'" 编译失败。
    implementation("com.github.ernestp.AndroidUSBCamera:libuvc:3.5.3")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}