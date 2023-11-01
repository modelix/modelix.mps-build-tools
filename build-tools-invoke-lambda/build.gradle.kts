plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
}

dependencies {
}


publishing {
    publications {
        create<MavenPublication>("invokeLambda") {
            groupId = project.group.toString()
            artifactId = "build-tools-invoke-lambda"
            version = project.version.toString()

            from(components["java"])
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
    withSourcesJar()
}
