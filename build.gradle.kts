plugins {
    id("com.android.application") version "9.3.0" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}

tasks.register("copyDebugApkToHome") {
    group = "build"
    description = "Copies the debug APK to the user home directory"
    dependsOn(":app:assembleDebug")
    val debugApk = layout.projectDirectory.file("app/build/outputs/apk/debug/app-debug.apk")
    val homeApk = providers.systemProperty("user.home").map { java.io.File(it, "TaraSec-debug.apk") }
    // Declare the one copied file, not the home directory containing this project.
    inputs.file(debugApk)
    outputs.file(homeApk)
    doLast {
        debugApk.asFile.copyTo(homeApk.get(), overwrite = true)
    }
}
