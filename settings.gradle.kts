pluginManagement {
    // convention plugins shared by all modules (kactor.kotlin-library, kactor.publish)
    includeBuild("build-logic")
}

plugins {
    // Apply the foojay-resolver plugin to allow automatic download of JDKs
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// repositories for every project, including the root (Dokka resolves its runtime there)
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kactor"

// core library, published as me.hchome:kactor
include("lib")