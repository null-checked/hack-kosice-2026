import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion"),
        )
        bundledPlugin("com.intellij.java")
        instrumentationTools()
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    // IntelliJ Platform ships its OWN kotlinx-coroutines-core at runtime. If
    // we bundle ours too, the plugin's classloader and the platform's load
    // separate copies of `FlowCollector`/`SafeCollector`, causing
    // ClassCastException when the platform's inline-completion machinery
    // hands us a collector — a real bug we hit in v0.3 when typing.
    // `compileOnly` gives us the API at compile time without shipping it.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // BasePlatformTestCase extends junit.framework.TestCase (JUnit 3). JUnit 4
    // bundles the junit.framework.* compat classes, which is what the IntelliJ
    // test framework transitively needs. Without this, compileTestKotlin fails
    // with "Cannot access 'junit.framework.TestCase'".
    testImplementation("junit:junit:4.13.2")
    // IntelliJ's test runner references `org.opentest4j.AssertionFailedError`
    // even under JUnit 3/4; the class isn't pulled in transitively by junit 4.
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-opt-in=kotlin.RequiresOptIn",
        )
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Leave untilBuild empty so the plugin isn't pinned to a specific upper IDE version.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    wrapper {
        gradleVersion = "8.10.2"
    }

    buildSearchableOptions {
        enabled = false
    }

    // Improve the dev loop:
    //  - Auto-reload the plugin without restarting the sandbox IDE (the
    //    `idea.auto.reload.plugins` flag + the `@Service` and `dynamic`
    //    extension points we already use let IntelliJ hot-swap our plugin
    //    when `./gradlew prepareSandbox` runs while the sandbox is open).
    //  - Auto-open the repo root so the user doesn't have to click through
    //    "Open project…" every time the sandbox boots.
    runIde {
        systemProperty("idea.auto.reload.plugins", "true")
        // Open the hack-kosice-2026 workspace directly. `projectDir` is
        // plugin/, its parent is the repo root — the project the user wants.
        args = listOf(project.projectDir.parentFile.absolutePath)
    }
}
