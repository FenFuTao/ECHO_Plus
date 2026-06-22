plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.fenfutao.echo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fenfutao.echo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.core.ktx)
    implementation(libs.activity.ktx)
    implementation(libs.material)
}
