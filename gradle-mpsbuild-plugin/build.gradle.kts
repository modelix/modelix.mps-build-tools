plugins {
    id("maven-publish")
    kotlin("jvm")
}

repositories {
    mavenCentral()
    maven { url = uri("https://projects.itemis.de/nexus/content/repositories/mbeddr") }
}

dependencies {
    implementation(project(":mps-build-tools"))
    implementation("org.zeroturnaround:zt-zip:1.14")
    implementation(gradleApi())
    testImplementation("junit:junit:4.13.2")
}

publishing {
    publications {
        create("gradlePlugin", MavenPublication::class.java) {
            groupId = project.group.toString()
            version = project.version.toString()
            artifactId = "gradle-plugin"
            from(components["java"])
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
}
