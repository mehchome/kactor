/**
 * Publishing conventions: Dokka HTML as the javadoc jar, and a `maven` publication of the java
 * component (main, sources and javadoc variants) to the remote Maven repository.
 *
 * The artifactId defaults to the project name; override it on `publishing.publications["maven"]`.
 */
plugins {
    id("kactor.kotlin-library")
    id("kactor.dokka")
    `maven-publish`
}

java {
    withJavadocJar()
}

// Fill the standard javadoc jar (and its published variant) with the Dokka HTML output;
// the JDK javadoc tool only reads Java sources, so it would otherwise be empty.
tasks.named<Jar>("javadocJar") {
    from(tasks.dokkaGeneratePublicationHtml.flatMap { it.outputDirectory })
}

/**
 * A publishing setting from the Gradle property [property] (`-P`, `~/.gradle/gradle.properties`),
 * else the environment variable [env]. Blank values count as unset, as CI expands unset
 * variables to empty strings.
 */
fun publishSetting(property: String, env: String): String? =
    providers.gradleProperty(property).filter { it.isNotBlank() }
        .orElse(providers.environmentVariable(env).filter { it.isNotBlank() })
        .orNull

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()

            // the java component carries the sources and javadoc variants into both POM and module metadata
            from(components["java"])
            pom {
                // lazily, so a module's own artifactId override is picked up
                name.set(provider { artifactId })
            }
        }
    }

    repositories {
        maven {
            name = "remote"
            // Development builds go to the snapshots repository; releases to releases.
            val isSnapshot = project.version.toString().let { it.endsWith("-SNAPSHOT") || "-dev." in it }
            url = uri(
                if (isSnapshot) {
                    publishSetting("maven.snapshotsUrl", "MAVEN_SNAPSHOTS_URL") ?: "https://libraries.hchome.me/snapshots"
                } else {
                    publishSetting("maven.releasesUrl", "MAVEN_RELEASES_URL") ?: "https://libraries.hchome.me/releases"
                }
            )
            // Only when given: once configured, Gradle requires credentials to have values.
            publishSetting("maven.username", "MAVEN_USERNAME")?.let { user ->
                credentials {
                    username = user
                    password = publishSetting("maven.password", "MAVEN_PASSWORD")
                }
            }
        }
    }
}