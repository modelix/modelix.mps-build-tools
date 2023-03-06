/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modelix.gradle.mpsbuild

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import org.modelix.buildtools.BuildScriptGenerator
import org.modelix.buildtools.newChild
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MPSBuildPlugin : Plugin<Project> {

    private lateinit var project: Project
    private lateinit var settings: MPSBuildSettings
    private lateinit var buildDir: File
    private lateinit var publicationsDir: File
    private lateinit var dependenciesDir: File
    private lateinit var antScriptFile: File
    private var generator: BuildScriptGenerator? = null
    private var dirsToMine: MutableSet<File> = mutableSetOf()

    override fun apply(project: Project) {
        this.project = project
        settings = project.extensions.create("mpsBuild", MPSBuildSettings::class.java)
        settings.setProject(project)
        buildDir = project.buildDir.resolve("mpsbuild").normalize()
        dependenciesDir = buildDir.resolve("dependencies")
        publicationsDir = buildDir.resolve("publications")
        antScriptFile = File(buildDir, "build-modules.xml")

        val taskCopyDependencies = project.tasks.register("copyDependencies", CopyDependencies::class.java) {
            settings.getTaskDependencies().forEach { dependsOn(it) }
            dependenciesConfig.set(settings.dependenciesConfig)
            mpsDependenciesConfig.set(settings.mpsDependenciesConfig)
            mpsDownloadUrl.set(settings.getMpsDownloadUrl())
            dependenciesTargetDir.set(dependenciesDir.normalize())
            targetDir.set(buildDir.resolve("mps"))
            doLast {
                this@MPSBuildPlugin.dirsToMine.add(dependenciesDir)
                mpsDir?.let { this@MPSBuildPlugin.dirsToMine.add(it) }
            }
        }

        val taskGenerateAntScript = project.tasks.register("generateMpsAntScript", GenerateAntScript::class.java) {
            dependsOn(taskCopyDependencies)
            generatorSettings.set(GeneratorSettings(
                settings.mpsHome,
                settings.resolveModulePaths(project.projectDir.toPath()).map { it.toString() },
                settings.generatorHeapSize,
                settings.getPublications(),
                settings.getMacros(project.projectDir.toPath()),
                buildDir))
            dependencyFiles.set(dirsToMine)
            antFile.set(antScriptFile)
        }
        val taskCheckConfig = project.tasks.register("checkMpsbuildConfig", CheckConfig::class.java) {
            dependsOn(taskGenerateAntScript)
            doFirst {
                generator.set(this@MPSBuildPlugin.generator)
            }
            this.settings.set(this@MPSBuildPlugin.settings)
        }
        val taskLoadPomDependencies = project.tasks.register("loadPomDependencies", LoadPomDependencies::class.java) {
            dependsOn(taskCheckConfig)
            settings.set(this@MPSBuildPlugin.settings)
            publicationToDnode.set(taskCheckConfig.get().publication2dnode)
            folderToOwningDependency.set(taskCopyDependencies.get().folderToOwningDependency)
            publicationsVersion.set(getPublicationsVersion())
            getPublication.set(taskCheckConfig.get().getPublication)
        }
        val taskAssembleMpsModules = project.tasks.register("assembleMpsModules", Exec::class.java) {
            dependsOn(taskGenerateAntScript)
            mustRunAfter(taskCheckConfig) // fail fast
            workingDir = antScriptFile.parentFile
            commandLine = listOf("ant", "-f", antScriptFile.absolutePath, "assemble")
            standardOutput = System.out
            errorOutput = System.err
            standardInput = System.`in`
        }

        val taskPackagePublications = project.tasks.register("packageMpsPublications", PackageMpsPublications::class.java) {
            dependsOn(taskCheckConfig)
            dependsOn(taskLoadPomDependencies)
            dependsOn(taskAssembleMpsModules)
            doFirst {
                generator.set(this@MPSBuildPlugin.generator)
            }
            publicationsDir.set(this@MPSBuildPlugin.publicationsDir)
            settings.set(this@MPSBuildPlugin.settings)
            publicationsVersion.set(getPublicationsVersion())
            publication2dnode.set(taskLoadPomDependencies.get().publicationToDnode)
        }
        project.tasks.withType(GenerateMavenPom::class.java).matching { it.name.matches(Regex(".+_.+_.+")) }.all {
            dependsOn(taskLoadPomDependencies)
        }
        project.afterEvaluate {
            settings.validate()
            this@MPSBuildPlugin.afterEvaluate(taskPackagePublications)
        }
    }

    private fun afterEvaluate(taskPackagePublications: TaskProvider<PackageMpsPublications>) {
        val publicationsVersion = getPublicationsVersion()
        val mavenPublications = HashMap<PublicationSettings, MavenPublication>()

        val mpsPublicationsConfig = project.configurations.create("mpsPublications")
        val publishing = project.extensions.findByType(PublishingExtension::class.java)
        publishing?.publications {
            for (publicationData in settings.getPublications()) {
                create("_"+ publicationData.name + "_", MavenPublication::class.java) {
                    val publication = this
                    mavenPublications[publicationData] = publication
                    publication.groupId = project.group.toString()
                    publication.artifactId = publicationData.name.toValidPublicationName()
                    publication.version = publicationsVersion

                    val zipFile = publicationsDir.resolve("${publicationData.name}.zip")
                    val artifact = project.artifacts.add(mpsPublicationsConfig.name, zipFile) {
                        type = "zip"
                        builtBy(taskPackagePublications)
                    }
                    publication.artifact(artifact)
                }
            }

            create("_all_", MavenPublication::class.java) {
                val publication = this
                publication.groupId = project.group.toString()
                publication.artifactId = "all"
                publication.version = publicationsVersion
                publication.pom {
                    withXml {
                        asElement().newChild("dependencies") {
                            for (publicationData in settings.getPublications()) {
                                newChild("dependency") {
                                    newChild("groupId", project.group.toString())
                                    newChild("artifactId", publicationData.name.toValidPublicationName())
                                    newChild("version", publicationsVersion)
                                }
                            }
                        }
                    }
                }
            }
        }

        val repositories = publishing?.repositories ?: listOf()
        val ownArtifactNames = settings.getPublications().map { it.name.toValidPublicationName() }.toSet() + "all"
        for (repo in repositories) {
            project.tasks.register("publishAllMpsPublicationsTo${repo.name.firstLetterUppercase()}Repository") {
                group = "publishing"
                description = "Publishes all Maven publications created by the mpsbuild plugin"
                dependsOn(project.tasks.withType(PublishToMavenRepository::class.java).matching {
                    it.repository == repo && ownArtifactNames.contains(it.publication.artifactId)
                })
            }
        }
        project.tasks.register("publishAllMpsPublications") {
            group = "publishing"
            description = "Publishes all Maven publications created by the mpsbuild plugin"
            dependsOn(project.tasks.withType(PublishToMavenRepository::class.java).matching {
                ownArtifactNames.contains(it.publication.artifactId)
            })
        }
    }

    private fun getPublicationsVersion() = if (project.version == Project.DEFAULT_VERSION) {
        null
    } else {
        ("" + project.version).ifEmpty { null }
    } ?: generateVersionNumber(settings.mpsMajorVersion)

    private fun generateVersionNumber(mpsVersion: String?): String {
        val timestamp = SimpleDateFormat("yyyyMMddHHmm").format(Date())
        val version = if (mpsVersion == null) timestamp else "$mpsVersion-$timestamp"
        println("##teamcity[buildNumber '${version}']")
        return version
    }

    private fun String.toValidPublicationName() = replace(Regex("[^A-Za-z0-9_\\-.]"), "_").lowercase()


}

private fun String.firstLetterUppercase() = if (isEmpty()) this else substring(0, 1).uppercase() + drop(1)