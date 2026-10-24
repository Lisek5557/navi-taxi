plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.taxinav"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.taxinav"
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
    }

    // Stały klucz testowy w repo: każda wersja z GitHuba instaluje się na poprzednią.
    // Przed publikacją w Google Play zastąp go prywatnym kluczem spoza repozytorium.
    signingConfigs {
        create("taxinavTest") {
            storeFile = file("taxinav-test.jks")
            storePassword = "taxinav"
            keyAlias = "taxinav"
            keyPassword = "taxinav"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("taxinavTest")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("taxinavTest")
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}
