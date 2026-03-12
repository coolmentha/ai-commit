plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "org.coolmentha"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.4")
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        create("IC", "2025.1.4.1")
        bundledModule("intellij.platform.vcs.impl")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add necessary plugin dependencies for compilation here, example:
        // bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }

        changeNotes = """
            <ul>
                <li>在 Commit 工具窗口中一键生成提交信息</li>
                <li>支持 OpenAI 兼容 API Key 与 Codex OAuth 双认证</li>
                <li>支持 Prompt 模板、自定义 Base URL 与提交格式模板校验</li>
            </ul>
        """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
