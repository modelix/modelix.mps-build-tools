package org.modelix.gradle.mpsbuild

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.property
import org.modelix.buildtools.DependencyGraph
import org.modelix.buildtools.FoundModule
import org.modelix.buildtools.ModuleId
import org.modelix.buildtools.newChild
import java.nio.file.Path
import javax.inject.Inject

abstract class LoadPomDependencies @Inject constructor(of: ObjectFactory): DefaultTask() {

    private val stubsPattern = Regex("stubs#([^#]+)#([^#]+)#([^#]+)")

    @Input
    val settings: Property<MPSBuildSettings> = of.property()

    @Input
    val publicationToDnode: MapProperty<MPSBuildSettings.PublicationSettings, DependencyGraph<FoundModule, ModuleId>.DependencyNode> =
        of.mapProperty()

    @Input
    val folderToOwningDependency: MapProperty<Path, ResolvedDependency> = of.mapProperty()

    @Input
    val publicationsVersion: Property<String> = of.property()

    @Input
    val getPublication: Property<(DependencyGraph<FoundModule, ModuleId>.DependencyNode)->MPSBuildSettings.PublicationSettings?> = of.property()

    @Input
    val mavenPublications = HashMap<MPSBuildSettings.PublicationSettings, MavenPublication>()

    @TaskAction
    fun load() {
        for (publication in settings.get().getPublications()) {
            val dnode = publicationToDnode.get()[publication]!!.getMergedNode()
            val pluginModuleNames = settings.get().getPluginModuleNames()
            val modulesAndStubs = dnode.modules.filter { !pluginModuleNames.contains(it.name) }
            val stubs = modulesAndStubs.filter { it.name.startsWith("stubs#") }.toSet()

            mavenPublications[publication]?.pom {
                withXml {
                    asElement().newChild("dependencies") {
                        // dependencies between own publications
                        for (dependency in dnode.getDependencies().mapNotNull(getPublication.get())) {
                            newChild("dependency") {
                                newChild("groupId", project.group.toString())
                                newChild("artifactId", dependency.name.toValidPublicationName())
                                newChild("version", publicationsVersion.get())
                            }
                        }

                        // dependencies to downloaded publications
                        val externalDependencies = (dnode.getDependencies() + dnode).flatMap { it.modules }
                            .mapNotNull { getOwningDependency(it.owner.path.getLocalAbsolutePath()) }
                            .distinct()
                        for (dependency in externalDependencies) {
                            newChild("dependency") {
                                newChild("groupId", dependency.moduleGroup)
                                newChild("artifactId", dependency.moduleName)
                                newChild("version", dependency.moduleVersion)
                            }
                        }

                        // dependencies to java libraries
                        for (stub in stubs) {
                            val match = stubsPattern.matchEntire(stub.name)
                                ?: throw RuntimeException("Failed to extract maven coordinates from ${stub.name}")
                            newChild("dependency") {
                                newChild("groupId", match.groupValues[1])
                                newChild("artifactId", match.groupValues[2])
                                newChild("version", match.groupValues[3])
                            }
                        }
                    }
                }
            }
        }
    }

    private fun String.toValidPublicationName() = replace(Regex("[^A-Za-z0-9_\\-.]"), "_").toLowerCase()

    private fun getOwningDependency(file: Path): ResolvedDependency? {
        var f: Path? = file.toAbsolutePath().normalize()
        while (f != null) {
            val owner = folderToOwningDependency.get()[f]
            if (owner != null) return owner
            f = f.parent
        }
        return null
    }
}