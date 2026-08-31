enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
// Keep the build root distinct from its published :ebp-kmp project so
// Gradle's type-safe root and child accessors cannot collide.
rootProject.name = "ebp-kmp-workspace"
include(":ebp-kmp")
include(":wire")
