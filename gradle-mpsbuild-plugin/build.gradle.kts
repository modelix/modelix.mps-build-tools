plugins {
    `maven-publish`
    `kotlin-dsl`
    kotlin("jvm")
}

repositories {
    mavenCentral()
    maven { url = uri("https://projects.itemis.de/nexus/content/repositories/mbeddr") }
}

dependencies {
    implementation(project(":mps-build-tools"))
    implementation("org.zeroturnaround:zt-zip:1.14")
    testImplementation("junit:junit:4.13.2")
}

gradlePlugin {
    plugins {
        register("mpsbuildPlugin") {
            id = project.group.toString()
            implementationClass = "org.modelix.gradle.mpsbuild.MPSBuildPlugin"
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
    withSourcesJar()
}
