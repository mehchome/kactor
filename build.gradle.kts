// Root project: cross-module aggregation only. Shared module configuration lives in the
// build-logic convention plugins, not here.

plugins {
    id("kactor.dokka")
}

dokka {
    moduleName.set("kactor API")
}

// Modules included in the aggregated API docs (`./gradlew :dokkaGenerate` -> build/dokka/html).
dependencies {
    dokka(project(":lib"))
}