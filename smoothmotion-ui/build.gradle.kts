import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "io.github.ivorisnoob.smoothmotion.ui"
    compileSdk = 36

    defaultConfig {
        // Media3 1.11 needs 23; the engine's GLES 3.1 calls exist from 21.
        // Thermal status (API 29) is read behind a version check.
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        // The toolchain and Media3 are pinned to what Koda tests on devices
        // (gradle/libs.versions.toml); bumps are deliberate, not lint's call.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        warningsAsErrors = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    api(project(":smoothmotion-media3"))
    api(libs.media3.ui)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "smoothmotion-ui"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("smoothmotion-ui")
                description.set("Real-time video frame interpolation for Android Media3 (smoothmotion-ui).")
                url.set("https://github.com/Ivorisnoob/smoothmotion-android")
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
            }
        }
    }
}
