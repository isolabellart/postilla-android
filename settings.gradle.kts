pluginManagement {
    // Kotlin 2.4 metadata needs R8 9.1.29+ when shrinking; override only R8,
    // keeping Postilla on its validated AGP 8.13 / Gradle 8.13 toolchain.
    buildscript {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools:r8:9.1.29")
        }
    }

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.objectbox") {
                useModule("io.objectbox:objectbox-gradle-plugin:${requested.version}")
            }
        }
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://mvn.breez.technology/releases") }
    }
}

rootProject.name = "dark-wisp-android"
include(":app")
