plugins {
    `kotlin-dsl`
}

dependencies {
    // plugins applied by the convention plugins in src/main/kotlin
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.dokka.gradle.plugin)
}