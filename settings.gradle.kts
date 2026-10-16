pluginManagement {
    repositories {
        // Google's mirror of Maven Central first: repo1 / repo.maven.apache.org
        // rate-limit (429) shared build hosts, which fails cold builds.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
        google()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
        google()
        mavenCentral()
    }
}

rootProject.name = "olo-calendar"
include(":composeApp")
