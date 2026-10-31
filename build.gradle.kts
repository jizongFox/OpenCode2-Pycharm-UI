import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "ai.opencode"
version = "2.2.3"
val pycharmVersion = providers.gradleProperty("pycharmVersion").getOrElse("2026.2.4")
val targetJavaVersion = providers.gradleProperty("targetJavaVersion").getOrElse("25")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        val localIdePath = providers.gradleProperty("localIdePath")
        if (localIdePath.isPresent) local(localIdePath.get())
        else pycharm(pycharmVersion)
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Terminal API
        bundledPlugin("org.jetbrains.plugins.terminal")

    }

    // HTTP client and JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
    implementation("com.google.code.gson:gson:2.11.0")
    
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            // No untilBuild - compatible with all future versions
        }

        changeNotes = """
            <h2>2.2.3</h2>
            <ul>
                <li>Aligns turn recovery with the OpenCode 2.2.5 session projection and shutdown semantics.</li>
                <li>Uses public V2 execution events and confirms missed completion through session timestamps.</li>
                <li>Reviews proven empty-file additions/deletions and refuses incomplete or binary restoration data.</li>
                <li>Adds an isolated real-release HTTP/SSE contract check without model execution.</li>
            </ul>
        """.trimIndent()
    }

    pluginVerification {
        ides {
            val localIdePath = providers.gradleProperty("localIdePath")
            if (localIdePath.isPresent) local(localIdePath.get())
            else create(IntelliJPlatformType.PyCharm, pycharmVersion) { }
        }
    }
}

tasks {
    withType<Test> {
        // Fixture and nested helper classes are not JUnit test suites.
        include("**/*Test.class")
        inputs.property("openCodeTestBinary", providers.environmentVariable("OPENCODE_TEST_BINARY").orElse(""))
    }

    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = targetJavaVersion
        targetCompatibility = targetJavaVersion
    }

    withType<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask> {
        systemProperty("ide.no.platform.update", "true")
    }

    // Disable buildSearchableOptions as it is flaky and often fails
    named("buildSearchableOptions") {
        enabled = false
    }
    named("prepareJarSearchableOptions") {
        enabled = false
    }
    named("jarSearchableOptions") {
        enabled = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(targetJavaVersion))
    }
}
