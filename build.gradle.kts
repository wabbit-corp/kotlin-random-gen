import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

repositories {
    mavenCentral()

    maven("https://jitpack.io")
}

group   = "one.wabbit"
version = "1.3.0"

plugins {
    kotlin("jvm") version "2.1.20"

    kotlin("plugin.serialization") version "2.1.20"

    id("maven-publish")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "one.wabbit"
            artifactId = "kotlin-random-gen"
            version = "1.3.0"
            from(components["java"])
        }
    }
}

dependencies {
    implementation("com.github.wabbit-corp:kotlin-data-need:1.2.0")
    implementation("com.github.wabbit-corp:kotlin-data:1.2.0")
    implementation("com.github.wabbit-corp:kotlin-base58:1.1.1")

    testImplementation(kotlin("test"))

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.0")

    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
}

java {
    targetCompatibility = JavaVersion.toVersion(21)
    sourceCompatibility = JavaVersion.toVersion(21)
}

tasks {
    withType<Test> {
        jvmArgs("-ea")

    }
    withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
    }
    withType<Javadoc> {
        options.encoding = Charsets.UTF_8.name()
    }

    withType<KotlinCompile> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xcontext-receivers")
        }
    }

    jar {
        setProperty("zip64", true)

    }
}
