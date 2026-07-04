// Top-level build file for the sleepwalker Android workspace.
// Plugin versions are declared in settings.gradle.kts via plugins {}.
plugins {
    id("com.android.application") apply false
    id("com.android.library") apply false
    id("org.jetbrains.kotlin.android") apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}