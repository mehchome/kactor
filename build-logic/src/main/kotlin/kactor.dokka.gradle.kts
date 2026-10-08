/**
 * Dokka conventions shared by every documented project: library modules (via kactor.publish)
 * and the root project, which aggregates the modules into one site.
 */
plugins {
    // KDoc -> HTML; the JDK javadoc tool only reads Java sources.
    id("org.jetbrains.dokka")
}

dokka {
    moduleName.set(project.name)
    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory = file("src/main/kotlin")
            // HEAD resolves to the repository's default branch.
            val moduleDir = projectDir.relativeTo(rootDir).invariantSeparatorsPath
            remoteUrl("https://github.com/mehchome/kactor/tree/HEAD/$moduleDir/src/main/kotlin")
        }
        // implementation details, not part of the public API
        perPackageOption {
            matchingRegex.set(""".*\.impl(\..*)?""")
            suppress.set(true)
        }
    }
}