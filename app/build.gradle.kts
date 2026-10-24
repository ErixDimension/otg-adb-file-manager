plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.otgmanager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.otgmanager"
        minSdk = 23
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    // Tambahkan dependensi Kadb setelah memastikan koordinat
    // dan versi rilisnya tersedia pada repository Maven.
    // Transport USB OTG harus diimplementasikan terpisah.
}
