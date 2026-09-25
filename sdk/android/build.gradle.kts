// Root build file: declares plugin versions so submodules can apply without
// specifying a version. Keep this in sync with demo-app/android/build.gradle.kts.
plugins {
    id("com.android.library") version "8.3.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.kapt") version "1.9.22" apply false
}
