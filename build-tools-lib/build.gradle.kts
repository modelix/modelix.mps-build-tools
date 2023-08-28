plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
}

dependencies {
    implementation("org.zeroturnaround:zt-zip:1.14")
    implementation("org.apache.commons:commons-text:1.9")
    implementation("commons-io:commons-io:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.0")
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("buildTools") {
            groupId = project.group.toString()
            artifactId = "build-tools-lib"
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
