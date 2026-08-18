plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.squarehub.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.squarehub.android"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Chạy kiểm tra kênh Telegram định kỳ trong nền (mỗi 15 phút), kể cả
    // khi app không mở, sống sót qua khởi động lại máy.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Đọc/phân tích trang xem trước công khai t.me/s/<kenh> để lấy bài mới.
    implementation("org.jsoup:jsoup:1.17.2")
}
