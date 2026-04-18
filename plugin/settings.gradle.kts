plugins {
    // Lets Gradle auto-provision the JDK declared in build.gradle.kts'
    // kotlin.jvmToolchain(21). Without this, builds fail on machines whose
    // JAVA_HOME points at a different major version.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "rubberduck-live-plugin"
