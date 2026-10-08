/**
 * Conventions shared by every kactor library module: Kotlin/JVM 21, compiler flags,
 * JUnit Platform tests and a sources jar.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
    idea
}

group = "me.hchome"
// The release workflow computes the version from the commits (see .github/workflows/release.yml)
// and passes it as APP_VERSION; local builds are a fixed snapshot.
version = providers.environmentVariable("APP_VERSION").filter { it.isNotBlank() }.orNull ?: "0.0.0-SNAPSHOT"

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // interface default methods without DefaultImpls (formerly -Xjvm-default=all)
        jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY
        // Pin the language and stdlib API level: this, not the compiler version, sets the minimum
        // Kotlin version of consumers. 2.4 makes context parameters and explicit backing fields stable.
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4
        apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4
    }
}

java {
    withSourcesJar()
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}