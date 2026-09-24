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

rootProject.name = "digiautotap"

// core: the readers, later the director and the skill loops. A plain JVM
// library with no android.* in it, so that its tests run on the PC against
// the oracle. app: the Android app that hangs core onto the accessibility
// service. See PLAN_ANDROID_APP.md, "Das Gerüst".
include(":core", ":app")
