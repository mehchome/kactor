plugins {
    id("kactor.publish")
}

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly(kotlin("reflect"))
    compileOnly(libs.coroutines)
    compileOnly(libs.slf4j.api)
    testImplementation(kotlin("stdlib"))
    testImplementation(kotlin("reflect"))
    testImplementation(libs.slf4j.api)
    testImplementation(libs.coroutines)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.logback)
}

dokka {
    moduleName.set("kactor")
}

publishing {
    publications.named<MavenPublication>("maven") {
        artifactId = "kactor"
    }
}