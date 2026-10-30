plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.u3dv"; compileSdk = 35
    defaultConfig { applicationId = "com.u3dv"; minSdk = 26; targetSdk = 28; versionCode = 1; versionName = "0.1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("com.google.ar:core:1.48.0") }
