package org.modelix.gradle.mpsbuild

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedConfiguration
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.property
import org.modelix.buildtools.GraphWithCyclesVisitor
import org.modelix.buildtools.newChild
import org.modelix.buildtools.newXmlDocument
import org.modelix.buildtools.xmlToString
import org.zeroturnaround.zip.ZipUtil
import java.io.File
import java.net.URL
import java.nio.file.Path
import javax.inject.Inject

abstract class CopyDependencies @Inject constructor(of: ObjectFactory): DefaultTask() {

    @InputFiles
    val dependenciesConfig: Property<Configuration> = of.property()

    @InputFiles
    @Optional
    val mpsDependenciesConfig: Property<Configuration> = of.property()

    @Input
    @Optional
    val mpsDownloadUrl: Property<URL> = of.property()

    @OutputDirectory
    val dependenciesTargetDir: DirectoryProperty = of.directoryProperty()

    @Internal
    val targetDir: DirectoryProperty = of.directoryProperty()

    @OutputFile
    @Optional
    var mpsDir: File? = null

    @Internal
    val folderToOwningDependency: MutableMap<Path, ResolvedDependency> = mutableMapOf()

    @TaskAction
    fun execute() {
        copyDependencies(dependenciesConfig.get(), dependenciesTargetDir.asFile.get())
        mpsDir = downloadMps(mpsDependenciesConfig.orNull, targetDir.asFile.get())
    }

    private fun copyDependencies(dependenciesConfiguration: Configuration, targetFolder: File) {
        val dependencies = dependenciesConfiguration.resolvedConfiguration.getAllDependencies()
        for (dependency in dependencies) {
            val files = dependency.moduleArtifacts.map { it.file }
            for (file in files) {
                copyDependency(file, targetFolder, dependency)
            }
        }
    }

    private fun copyDependency(file: File, targetFolder: File, dependency: ResolvedDependency) {
        when (file.extension) {
            "jar" -> {
                generateStubsSolution(dependency, targetFolder.resolve("stubs"))
            }
            "zip" -> {
                val targetFile = targetFolder
                    .resolve("modules")
                    .resolve(dependency.moduleGroup)
                    .resolve(dependency.moduleName)
                //.resolve(file.name)
                folderToOwningDependency[targetFile.absoluteFile.toPath().normalize()] = dependency
                copyAndUnzip(file, targetFile)
            }
            else -> println("Ignored file $file from dependency ${dependency.module.id}")
        }
    }

    private fun getStubSolutionName(dependency: ResolvedDependency): String {
//                        val clean: (String)->String = { it.replace(Regex("[^a-zA-Z0-9]"), "_") }
//                        val group = clean(it.moduleGroup)
//                        val artifactId = clean(it.moduleName)
//                        val version = clean(it.moduleVersion)
//                        "stubs.$group.$artifactId.$version"
        return "stubs#" + dependency.module.id.toString().replace(":", "#")
    }

    private fun ResolvedConfiguration.getAllDependencies(): List<ResolvedDependency> {
        val allDependencies: MutableList<ResolvedDependency> = ArrayList()
        object : GraphWithCyclesVisitor<ResolvedDependency>() {
            override fun onVisit(element: ResolvedDependency) {
                allDependencies.add(element)
                visit(element.children)
            }
        }.visit(firstLevelModuleDependencies)
        return allDependencies
    }

    private fun copyAndUnzip(sourceFile: File, targetFile: File) {
        targetFile.parentFile.mkdirs()
        if (sourceFile.extension == "zip") {
            if (targetFile.exists()) targetFile.deleteRecursively()
            ZipUtil.unpack(sourceFile, targetFile)
        } else {
            sourceFile.copyTo(targetFile, true)
        }
    }

    private fun generateStubsSolution(dependency: ResolvedDependency, stubsDir: File) {
        val solutionName = getStubSolutionName(dependency)
        val jars = dependency.moduleArtifacts.map { it.file }.filter { it.extension == "jar" }
        val xml = newXmlDocument {
            newChild("solution") {
                setAttribute("name", solutionName)
                setAttribute("pluginKind", "PLUGIN_OTHER")
                setAttribute("moduleVersion", "0")
                setAttribute("uuid", "~$solutionName")
                newChild("facets") {
                    newChild("facet") {
                        setAttribute("type", "java")
                    }
                }
                newChild("models") {
                    for (jar in jars) {
                        newChild("modelRoot") {
                            setAttribute("type", "java_classes")
                            setAttribute("contentPath", jar.parentFile.absolutePath)
                            newChild("sourceRoot") {
                                setAttribute("location", jar.name)
                            }
                        }
                    }
                }
                newChild("dependencies") {
                    newChild("dependency", "6354ebe7-c22a-4a0f-ac54-50b52ab9b065(JDK)") {
                        setAttribute("reexport", "true")
                    }
                    for (transitiveDep in dependency.children) {
                        val n = getStubSolutionName(transitiveDep)
                        newChild("dependency", "~$n($n)") {
                            setAttribute("reexport", "true")
                        }
                    }
                }
                newChild("stubModelEntries") {
                    for (jar in jars) {
                        newChild("stubModelEntry") {
                            setAttribute("path", jar.absolutePath)
                        }
                    }
                }
            }
        }
        val solutionFile = stubsDir.resolve(solutionName).resolve("$solutionName.msd")
        solutionFile.parentFile.mkdirs()
        solutionFile.writeText(xmlToString(xml))
    }

    private fun downloadMps(mpsDependenciesConfig: Configuration?, targetDir: File): File {
        var mpsDir: File? = null
        mpsDependenciesConfig?.resolvedConfiguration?.lenientConfiguration?.let {
            for (file in it.files) {
                val targetFile = targetDir.resolve(file.name)
                if (!targetFile.exists()) {
                    copyAndUnzip(file, targetFile)
                }
                mpsDir = targetFile
            }
        }

        if (mpsDir == null) {
            if (mpsDownloadUrl.isPresent) {
                val url = mpsDownloadUrl.get()
                val file = targetDir.resolve(url.toString().substringAfterLast("/"))
                if (!file.exists()) {
                    println("Downloading $url")
                    file.parentFile.mkdirs()
                    url.openStream().use { istream ->
                        file.outputStream().use { ostream ->
                            istream.copyTo(ostream)
                        }
                    }
                }
                if (file.isFile) {
                    ZipUtil.explode(file)
                }
                mpsDir = file
            }
        }
        return mpsDir ?: targetDir
    }

}