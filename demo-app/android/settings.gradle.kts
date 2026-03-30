pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "asphalt-demo"

// Reference the SDK module from the parent directory
include(":asphalt-sdk")
project(":asphalt-sdk").projectDir = File("../../sdk/android/asphalt-sdk")

include(":app")
