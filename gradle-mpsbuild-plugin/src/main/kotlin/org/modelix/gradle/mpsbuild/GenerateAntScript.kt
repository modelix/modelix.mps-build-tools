package org.modelix.gradle.mpsbuild

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import org.modelix.buildtools.BuildScriptGenerator
import org.modelix.buildtools.FoundModule
import org.modelix.buildtools.ModuleResolver
import org.modelix.buildtools.ModulesMiner
import java.io.File
import java.nio.file.Paths
import javax.inject.Inject

abstract class GenerateAntScript @Inject constructor(of: ObjectFactory): DefaultTask() {

    @Input
    val generatorSettings: Property<GeneratorSettings> = of.property()

    @InputFiles
    val dependencyFiles: SetProperty<File> = of.setProperty(File::class.java)

    @OutputFile
    val antFile: RegularFileProperty = of.fileProperty()

    @Internal
    lateinit var generator: BuildScriptGenerator

    @TaskAction
    fun generate() {
        generator = createBuildScriptGenerator(generatorSettings.get(), dependencyFiles.get())
        val xml = generator.generateXML()
        antFile.asFile.get().writeText(xml)
    }

    private fun createBuildScriptGenerator(generatorSettings: GeneratorSettings,
                                           dependencyFiles: Set<File>): BuildScriptGenerator {
        val modulesMiner = ModulesMiner()
        val mPaths = generatorSettings.modulePaths.map { Paths.get(it) } //settings.resolveModulePaths(project.projectDir.toPath())
        for (modulePath in mPaths) {
            modulesMiner.searchInFolder(modulePath.toFile())
        }
        for (dependencyFile in dependencyFiles) {
            modulesMiner.searchInFolder(dependencyFile)
        }
        if (generatorSettings.mpsPath != null) {
            val mpsHome = this.project.projectDir.toPath()
                .resolve(Paths.get(generatorSettings.mpsPath)).normalize().toFile()
            if (!mpsHome.exists()) {
                throw RuntimeException("$mpsHome doesn't exist")
            }
            modulesMiner.searchInFolder(mpsHome)
        }
        val resolver = ModuleResolver(modulesMiner.getModules(), emptySet())
        val modulesToGenerate = generatorSettings.publications
            .flatMap { resolvePublicationModules(it, resolver) }.map { it.moduleId }
        val generator = BuildScriptGenerator(
            modulesMiner = modulesMiner,
            modulesToGenerate = modulesToGenerate,
            ignoredModules = emptySet(),
            initialMacros = generatorSettings.macros,//settings.getMacros(project.projectDir.toPath()),
            buildDir = generatorSettings.buildDir
        )
        generator.generatorHeapSize = generatorSettings.heapSize
        generator.ideaPlugins += generatorSettings.publications.flatMap { it.ideaPlugins }.map { pluginSettings ->
            val moduleName = pluginSettings.getImplementationModuleName()
            val module = (modulesMiner.getModules().getModules().values.find { it.name == moduleName }
                ?: throw RuntimeException("module $moduleName not found"))
            BuildScriptGenerator.IdeaPlugin(module, "" + project.version, pluginSettings.pluginXml)
        }
        return generator
    }

    private fun resolvePublicationModules(publication: PublicationSettings, resolver: ModuleResolver): List<FoundModule> {
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