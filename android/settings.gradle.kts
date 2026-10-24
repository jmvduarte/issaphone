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
        // Repositório oficial do Linphone SDK (SIP)
        maven { url = uri("https://download.linphone.org/maven_repository") }
    }
}

rootProject.name = "IssaFone"
include(":app")
