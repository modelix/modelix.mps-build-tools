plugins {
    `maven-publish`
    `kotlin-dsl`
    kotlin("jvm")
}

repositories {
    mavenCentral()
    maven { url = uri("https://artifacts.itemis.cloud/repository/maven-mps/") }
}

dependencies {
    implementation(project(":build-tools-lib"))
    implementation(project(":build-tools-invoke-lambda"))
    implementation(libs.kotlin.coroutines.core)
    implementation(libs.zt.zip)
    testImplementation("junit:junit:4.13.2")
}

gradlePlugin {
    plugins {
        register("mpsbuildPlugin") {
            id = "org.modelix.mps.build-tools"
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

kotlin {
    compilerOptions {
        // https://youtrack.jetbrains.com/issue/KT-74984
        freeCompilerArgs.add("-Xignore-const-optimization-errors")
    }
}
