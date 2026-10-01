plugins {
    // AGP 9 compiles Kotlin itself; the Kotlin Android plugin is not applied.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

allprojects {
    // JitPack rewrites the group to com.github.Ivorisnoob.smoothmotion-android.
    group = "io.github.ivorisnoob.smoothmotion"
    version = providers.gradleProperty("smoothmotion.version").get()
}
