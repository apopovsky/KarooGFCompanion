pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("USERNAME"))
                    .orNull
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("TOKEN"))
                    .orNull
            }
        }
    }
}

rootProject.name = "KarooGFCompanion"
include(":shared")
include(":app")

val simulatorProjectDir = listOf(
    file("../KarooExtensionSimulator"),
    file("C:/Users/apopo/source/repos/KarooExtensionSimulator"),
).firstOrNull { it.exists() }

if (simulatorProjectDir != null && file("simulator").exists()) {
    include(":simulator")
    includeBuild(simulatorProjectDir) {
        dependencySubstitution {
            substitute(module("com.itl.karoo:core")).using(project(":core"))
        }
    }
}
