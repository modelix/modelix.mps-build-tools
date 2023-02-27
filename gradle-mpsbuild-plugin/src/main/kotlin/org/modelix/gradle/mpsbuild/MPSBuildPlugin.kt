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
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import org.modelix.buildtools.*
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.*

class MPSBuildPlugin : Plugin<Project> {

    private lateinit var project: Project
    private lateinit var settings: MPSBuildSettings
    private lateinit var buildDir: File
    private lateinit var publicationsDir: File
    private lateinit var dependenciesDir: File
    private lateinit var antScriptFile: File

    override fun apply(project: Project) {
        this.project = project
        settings = project.extensions.create("mpsBuild", MPSBuildSettings::class.java)
        settings.setProject(project)
        buildDir = project.buildDir.resolve("mpsbuild").normalize()
        dependenciesDir = buildDir.resolve("dependencies")
        publicationsDir = buildDir.resolve("publications")
        antScriptFile = File(buildDir, "build-modules.xml")

        val folder2owningDependency = HashMap<Path, ResolvedDependency>()
        val taskCopyDependencies = project.tasks.register("copyDependencies", CopyDependencies::class.java) {
            settings.getTaskDependencies().forEach { this.dependsOn(it) }
            this.dependenciesConfig.set(settings.dependenciesConfig)
            this.dependenciesTargetDir.set(dependenciesDir.normalize())
            this.folderToOwningDependency.set(folder2owningDependency)
            this.mpsDir.set(buildDir.resolve("mps"))
        }
        val dirsToMine = setOfNotNull(dependenciesDir, taskCopyDependencies.get().mpsDir.asFile.get())
        val buildScriptGenerator = createBuildScriptGenerator(settings, project, buildDir, dirsToMine)

        val taskGenerateAntScript = project.tasks.register("generateMpsAntScript", GenerateAntScript::class.java) {
            this.dependsOn(taskCopyDependencies)
            this.generator.set(buildScriptGenerator)
            this.antFile.set(antScriptFile)
        }
        val taskCheckConfig = project.tasks.register("checkMpsbuildConfig", CheckConfig::class.java) {
            this.dependsOn(taskGenerateAntScript)
            this.generator.set(buildScriptGenerator)
            this.settings.set(this@MPSBuildPlugin.settings)
        }
        val taskLoadPomDependencies = project.tasks.register("loadPomDependencies", LoadPomDependencies::class.java) {
            this.dependsOn(taskCheckConfig)
            this.settings.set(this@MPSBuildPlugin.settings)
            this.publicationToDnode.set(taskCheckConfig.get().publication2dnode)
            this.folderToOwningDependency.set(folder2owningDependency)
            this.publicationsVersion.set(getPublicationsVersion())
            this.getPublication.set(taskCheckConfig.get().getPublication)
        }
        val taskAssembleMpsModules = project.tasks.register("assembleMpsModules", Exec::class.java) {
            this.dependsOn(taskGenerateAntScript)
            this.mustRunAfter(taskCheckConfig) // fail fast
            workingDir = antScriptFile.parentFile
            commandLine = listOf("ant", "-f", antScriptFile.absolutePath, "assemble")
            standardOutput = System.out
            errorOutput = System.err
            standardInput = System.`in`
        }

        val taskPackagePublications = project.tasks.register("packageMpsPublications", PackageMpsPublications::class.java) {
            this.dependsOn(taskCheckConfig)
            this.dependsOn(taskLoadPomDependencies)
            this.dependsOn(taskAssembleMpsModules)
            this.publicationsDir.set(this@MPSBuildPlugin.publicationsDir)
            this.generator.set(buildScriptGenerator)
            this.settings.set(this@MPSBuildPlugin.settings)
            this.publicationsVersion.set(getPublicationsVersion())
            this.publication2dnode.set(taskLoadPomDependencies.get().publicationToDnode)
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
        val mavenPublications = HashMap<MPSBuildSettings.PublicationSettings, MavenPublication>()

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

    private fun String.toValidPublicationName() = replace(Regex("[^A-Za-z0-9_\\-.]"), "_").toLowerCase()

    private fun createBuildScriptGenerator(settings: MPSBuildSettings,
                                           project: Project,
                                           buildDir: File,
                                           dependencyFiles: Set<File>): BuildScriptGenerator {
        val modulesMiner = ModulesMiner()
        for (modulePath in settings.resolveModulePaths(project.projectDir.toPath())) {
            modulesMiner.searchInFolder(modulePath.toFile())
        }
        for (dependencyFile in dependencyFiles) {
            modulesMiner.searchInFolder(dependencyFile)
        }
        val mpsPath = settings.mpsHome
        if (mpsPath != null) {
            val mpsHome = project.projectDir.toPath().resolve(Paths.get(mpsPath)).normalize().toFile()
            if (!mpsHome.exists()) {
                throw RuntimeException("$mpsHome doesn't exist")
            }
            modulesMiner.searchInFolder(mpsHome)
        }
        val resolver = ModuleResolver(modulesMiner.getModules(), emptySet())
        val modulesToGenerate = settings.getPublications()
            .flatMap { resolvePublicationModules(it, resolver) }.map { it.moduleId }
        val generator = BuildScriptGenerator(
            modulesMiner = modulesMiner,
            modulesToGenerate = modulesToGenerate,
            ignoredModules = emptySet(),
            initialMacros = settings.getMacros(project.projectDir.toPath()),
            buildDir = buildDir
        )
        generator.generatorHeapSize = settings.generatorHeapSize
        generator.ideaPlugins += settings.getPublications().flatMap { it.ideaPlugins }.map { pluginSettings ->
            val moduleName = pluginSettings.getImplementationModuleName()
            val module = (modulesMiner.getModules().getModules().values.find { it.name == moduleName }
                ?: throw RuntimeException("module $moduleName not found"))
            BuildScriptGenerator.IdeaPlugin(module, "" + project.version, pluginSettings.pluginXml)
        }
        return generator
    }

    private fun resolvePublicationModules(publication: MPSBuildSettings.PublicationSettings, resolver: ModuleResolver): List<FoundModule> {
        val modulesToGenerate: MutableList<FoundModule> = ArrayList()
        val includedPaths = publication.resolveIncludedModules(project.projectDir.toPath())
        val includedModuleNames = publication.getIncludedModuleNames()
        val foundModuleNames: MutableSet<String> = HashSet()
        if (includedPaths != null || includedModuleNames != null) {
            for (module in resolver.availableModules.getModules().values) {
                if (includedModuleNames != null && includedModuleNames.contains(module.name)) {
                    modulesToGenerate.add(module)
                    foundModuleNames.add(module.name)
                } else if (includedPaths != null) {
                    val modulePath = module.owner.path.getLocalAbsolutePath()
                    if (includedPaths.any(modulePath::startsWith)) {
                        modulesToGenerate.add(module)
                    }
                }
            }
        }

        val missingModuleNames = includedModuleNames?.minus(foundModuleNames)?.sorted()
            ?: emptyList()

        if (missingModuleNames.isNotEmpty()) {
            throw RuntimeException("Modules not found: $missingModuleNames")
        }
        return modulesToGenerate
    }
}

private fun String.firstLetterUppercase() = if (isEmpty()) this else substring(0, 1).toUpperCase() + drop(1)