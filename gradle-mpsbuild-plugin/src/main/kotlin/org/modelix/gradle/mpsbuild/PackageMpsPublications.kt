package org.modelix.gradle.mpsbuild

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.property
import org.modelix.buildtools.*
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

abstract class PackageMpsPublications @Inject constructor(of: ObjectFactory): DefaultTask() {

    @InputDirectory
    val publicationsDir: DirectoryProperty = of.directoryProperty()

    @Input
    val generator: Property<BuildScriptGenerator> = of.property()

    @Input
    val settings: Property<MPSBuildSettings> = of.property()

    @Input
    val publicationsVersion: Property<String> = of.property()

    @Input
    val publication2dnode: MapProperty<MPSBuildSettings.PublicationSettings, DependencyGraph<FoundModule, ModuleId>.DependencyNode> =
        of.mapProperty()

    @TaskAction
    fun execute() {
        val generator = generator.get()
        val settings = settings.get()
        val packagedModulesDir = generator.getPackagedModulesDir()
        val generatedPlugins = generator.getGeneratedPlugins().entries.associate { it.key.name to it.value }
        val pluginModuleNames = settings.getPluginModuleNames()
        for (publication in settings.getPublications()) {
            val dnode = publication2dnode.get()[publication]!!.getMergedNode()
            val modulesAndStubs = dnode.modules.filter { !pluginModuleNames.contains(it.name) }
            val stubs = modulesAndStubs.filter { it.name.startsWith("stubs#") }.toSet()
            val modules = modulesAndStubs - stubs
            val generatedFiles = modules.map { it.owner }.filterIsInstance<SourceModuleOwner>()
                .distinct().flatMap { generator.getGeneratedFiles(it) }.map { it.absoluteFile.normalize() }
            val zipFile = publicationsDir.asFile.get().resolve("${publication.name}.zip")
            zipFile.parentFile.mkdirs()
            zipFile.outputStream().use { os ->
                ZipOutputStream(os).use { zipStream ->
                    val packFile: (File, Path, Path)->Unit = { file, path, parent ->
                        val relativePath = parent.relativize(path).toString()
                        require(!path.toString().startsWith("..") && !path.toString().contains("/../")) {
                            "$file expected to be inside $parent"
                        }
                        val entry = ZipEntry(relativePath)
                        zipStream.putNextEntry(entry)
                        file.inputStream().use { istream -> istream.copyTo(zipStream) }
                        zipStream.closeEntry()
                    }
                    for (file in generatedFiles) {
                        packFile(file, file.toPath(), packagedModulesDir.parentFile.toPath())
                    }
                    for (ideaPlugin in publication.ideaPlugins) {
                        val pluginFolder = generatedPlugins[ideaPlugin.getImplementationModuleName()]
                            ?: throw RuntimeException("Output for plugin '${ideaPlugin.getImplementationModuleName()}' not found")
                        for (file in pluginFolder.walk()) {
                            if (file.isFile) {
                                packFile(file, file.toPath(), pluginFolder.parentFile.parentFile.toPath())
                            }
                        }
                    }
                }
            }
        }

        println("Version $publicationsVersion ready for publishing")
    }
}