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
        // Hosts the wireless debugging library.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.MuntashirAkon.*") }
        }
    }
}
rootProject.name = "Loadout"
include(":app")
