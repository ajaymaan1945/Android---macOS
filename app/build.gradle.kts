plugins { alias(libs.plugins.android.application) }

android {
    namespace = "com.maan.connect"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = "com.maan.connect"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "2.1.0"
    }
    buildTypes { release { isMinifyEnabled = false; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    testImplementation(libs.junit)
}
