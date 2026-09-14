plugins {
    id("com.android.application") version "9.3.0" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}


tasks.register<org.gradle.api.tasks.Copy>("copyDebugApkToHome") {
    group = "build"
    description = "Copies the debug APK to the user home directory"
    dependsOn(":app:assembleDebug")
    from("app/build/outputs/apk/debug/app-debug.apk")
    into(System.getProperty("user.home"))
    rename { "TaraSec-debug.apk" }
}
