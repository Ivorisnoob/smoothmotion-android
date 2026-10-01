import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.ivorisnoob.smoothmotion.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ivorisnoob.smoothmotion.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = providers.gradleProperty("smoothmotion.version").get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    implementation(project(":smoothmotion-ui"))
    implementation(libs.androidx.activity)
}
