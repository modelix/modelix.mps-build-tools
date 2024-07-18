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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.getValue
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.provideDelegate
import org.gradle.kotlin.dsl.setValue
import org.modelix.buildtools.Macros
import org.modelix.buildtools.readXmlFile
import org.modelix.buildtools.runner.BundledPluginPath
import org.modelix.buildtools.runner.ExternalPluginPath
import org.modelix.buildtools.runner.MPSRunnerConfig
import org.modelix.buildtools.runner.PluginConfig
import org.w3c.dom.Document
import java.io.File
import java.net.URL
import java.nio.file.Path
import java.util.stream.Collectors

open class MPSBuildSettings(val project: Project) {
    private val mpsVersionPattern = Regex("""(\d+\.\d+)(\.\d+)?(-.*)?""")
    val dependenciesConfig: Configuration = project.configurations.create("mpsBuild-dependencies")
    var mpsDependenciesConfig: Configuration? = null
    var parentPublicationName: String? = "all"
    private val publications: MutableMap<String, PublicationSettings> = LinkedHashMap()

    var mpsHome: String?
        get() = mpsHome_property.map { it.asFile.absolutePath }.orNull
        set(value) { mpsHome_property.set(value?.let { File(it) }) }

    /**
     * The JAVA_HOME used when running the ANT script
     */
    var javaHome: File? = null
    private val searchPaths: MutableList<String> = ArrayList()
    private val macros: MutableMap<String, String> = HashMap()
    var generatorHeapSize: String = "2G"
    var mpsMajorVersion: String? = null
    private var mpsMinorVersion: String? = null
    var mpsFullVersion: String? = null
    private var mpsDownloadUrl: URL? = null
    private val taskDependencies: MutableList<Any> = ArrayList()
    internal val runConfigs: MutableMap<String, RunMPSTaskConfig> = HashMap()

    val mpsHome_property: DirectoryProperty = project.objects.directoryProperty()

    fun getTaskDependencies(): List<Any> = taskDependencies

    fun dependsOn(vararg tasks: Any) {
        taskDependencies.addAll(tasks)
    }

    fun getPublications(): List<PublicationSettings> = publications.values.toList()

    fun getPluginModuleNames(): Set<String> {
        return getPublications().flatMap { it.ideaPlugins }.map { it.getImplementationModuleName() }.toSet()
    }

    fun mpsVersion(v: String) {
        require(mpsFullVersion == null) { "MPS version is already set ($mpsFullVersion)" }
        val match = mpsVersionPattern.matchEntire(v)
            ?: throw RuntimeException("Not a valid MPS version: $v")
        mpsFullVersion = v
        mpsMajorVersion = match.groupValues[1]
        mpsMinorVersion = match.groupValues.getOrNull(2)
        mpsFromMaven(getMpsMavenCoordinates())
    }

    fun getMpsDownloadUrl(): URL? {
        if (mpsDownloadUrl != null) return mpsDownloadUrl
        if (mpsFullVersion != null) {
            return URL("https://download.jetbrains.com/mps/$mpsMajorVersion/MPS-$mpsFullVersion.zip")
        }
        return null
    }

    fun getMpsMavenCoordinates(): String {
        return "com.jetbrains:mps:$mpsFullVersion"
    }

    fun externalModules(coordinates: Any) {
        project.dependencies.add(dependenciesConfig.name, coordinates)
    }

    fun stubs(coordinates: Any) {
        project.dependencies.add(dependenciesConfig.name, coordinates)
    }

    fun mps(spec: Any) {
        if (spec is String && mpsVersionPattern.matches(spec)) {
            mpsVersion(spec)
        } else if (spec is String && spec.contains("://")) {
            mpsDownloadUrl = URL(spec)
        } else {
            mpsFromMaven(spec)
        }
    }

    fun mpsFromMaven(coordinates: Any) {
        require(mpsDependenciesConfig == null) { "MPS dependency is already set" }
        mpsDependenciesConfig = project.configurations.create("mpsBuild-mps")
        project.dependencies.add(mpsDependenciesConfig!!.name, coordinates)
    }

    fun mpsHome(value: String) {
        mpsHome_property.set(File(value))
    }

    fun usingExistingMps(): Boolean {
        return mpsHome != null
    }

    fun validate() {
        // nothing to check at the moment
    }

    fun search(path: String) {
        searchPaths.add(path)
    }

    fun macro(name: String, value: String) {
        macros[name] = value
    }

    fun resolveModulePaths(workdir: Path): List<Path> {
        return if (searchPaths.isEmpty()) {
            listOf(workdir)
        } else {
            searchPaths.map { workdir.resolve(it).normalize() }.distinct().toList()
        }
    }

    fun getMacros(workdir: Path): Macros {
        val resolvedMacros: MutableMap<String, Path> = HashMap()
        for ((key, value) in macros) {
            resolvedMacros[key] = workdir.resolve(value).toAbsolutePath().normalize()
        }
        return Macros(resolvedMacros)
    }

    fun publication(name: String, action: Action<PublicationSettings>): PublicationSettings {
        require(!publications.containsKey(name)) { "Duplicate publication '$name'" }
        require(name != "all") { "publication name '$name' already exists" }
        require(name != parentPublicationName) { "publication name '$name' already exists" }
        val publication = PublicationSettings(name)
        publications[name] = publication
        action.execute(publication)
        return publication
    }

    fun runMPS(name: String, action: Action<RunMPSTaskConfig>): RunMPSTaskConfig {
        return runConfigs.getOrPut(name) { RunMPSTaskConfig(name) }.also { action.execute(it) }
    }

    fun disableParentPublication() {
        parentPublicationName = null
    }

    inner class IdeaPluginSettings {
        private var implementationModule: String? = null
        var description: String? = null
        var pluginXml: Document? = null
        fun getImplementationModuleName() = implementationModule
            ?: throw RuntimeException("No implementation module specified for the IDEA plugin")
        fun implementationModule(name: String) {
            require(implementationModule == null) {
                "Only one implementation module is supported. It's already set to $implementationModule"
            }
            implementationModule = name
        }
        fun description(value: String) {
            description = value
        }
        fun pluginXml(content: String) {
            pluginXml = readXmlFile(content.byteInputStream(), "pluginXml of $implementationModule")
        }
    }

    inner class PublicationSettings(val name: String) {
        private val includedPaths: MutableList<String> = ArrayList()
        private val includedModuleNames: MutableSet<String> = HashSet()
        val ideaPlugins: MutableList<IdeaPluginSettings> = ArrayList()

        fun includePath(pathToInclude: String) {
            includedPaths.add(pathToInclude)
        }

        fun resolveIncludedModules(workdir: Path): List<Path>? {
            return if (includedPaths.isEmpty()) null else includedPaths.stream().map { path: String? -> workdir.resolve(path).toAbsolutePath().normalize() }.distinct().collect(Collectors.toList())
        }

        fun getIncludedModuleNames(): Set<String>? {
            return if (includedModuleNames.isEmpty()) null else includedModuleNames
        }

        fun module(moduleName: String) {
            includedModuleNames.add(moduleName)
        }

        fun ideaPlugin(action: Action<IdeaPluginSettings>): IdeaPluginSettings {
            val plugin = IdeaPluginSettings()
            ideaPlugins += plugin
            action.execute(plugin)
            return plugin
        }
    }

    inner class RunMPSTaskConfig(val name: String) {
        internal var configProperty: Property<MPSRunnerConfig> = project.objects.property<MPSRunnerConfig>().also { it.set(MPSRunnerConfig()) }
        internal var config by configProperty
        internal val classPathFromConfigurations: MutableSet<Configuration> = LinkedHashSet()
        internal val taskDependencies: MutableList<Any> = ArrayList()
        internal var implementationLambda: (() -> Any)? = null
        internal var result: CompletableDeferred<Any>? = null

        fun updateConfig(body: (MPSRunnerConfig) -> MPSRunnerConfig): RunMPSTaskConfig {
            val oldConfig = config
            val newConfig = body(oldConfig)
            config = newConfig
            return this
        }

        fun mainClassName(name: String) = updateConfig { it.copy(mainClassName = name) }
        fun mainMethodName(name: String) = updateConfig { it.copy(mainMethodName = name) }
        fun mainMethodFqName(fqName: String): RunMPSTaskConfig {
            return mainClassName(fqName.substringBeforeLast("."))
                .mainMethodName(fqName.substringAfterLast("."))
        }
        fun implementation(body: () -> Unit) = also { implementationLambda = body }

        @OptIn(ExperimentalCoroutinesApi::class)
        fun <R> implementation(body: () -> R, onSuccess: (R) -> Unit) {
            implementationLambda = body as () -> Any
            val deferred = CompletableDeferred<R>().also { result = it as CompletableDeferred<Any> }
            deferred.invokeOnCompletion {
                onSuccess(deferred.getCompleted())
            }
        }
        fun includeConfiguration(c: Configuration) {
            classPathFromConfigurations += c
        }
        fun includeConfiguration(name: String) {
            project.configurations.named(name) {
                includeConfiguration(this)
            }
        }
        fun includeProjectRuntime() {
            project.configurations.named("runtimeClasspath") {
                includeConfiguration(this)
            }
            taskDependencies += "jar"
            project.tasks.named("jar", Jar::class.java) {
                classPathElement(this.archiveFile.get().asFile)
            }
        }
        fun moduleDir(dir: File) = updateConfig { it.copy(additionalModuleDirs = it.additionalModuleDirs + dir) }
        fun moduleDependency(reference: String) = updateConfig { it.copy(additionalModuleDependencies = it.additionalModuleDependencies + reference) }
        fun moduleDependencies(dir: File) = updateConfig {
            it.copy(additionalModuleDependencyDirs = it.additionalModuleDependencyDirs + dir)
        }
        fun classPathElement(fileOrDir: File) = updateConfig { it.copy(classPathElements = it.classPathElements + fileOrDir) }
        fun jarLibrary(file: File) = updateConfig { it.copy(classPathElements = it.classPathElements + file) }
        fun jarLibraries(dir: File) = updateConfig { it.copy(jarFolders = it.classPathElements + dir) }
        fun jvmArg(arg: String) = updateConfig { it.copy(jvmArgs = it.jvmArgs + arg) }
        fun bundledPlugin(id: String, dir: File) = updateConfig { it.copy(plugins = it.plugins + PluginConfig(id, BundledPluginPath(dir))) }
        fun externalPlugin(id: String, dir: File) = updateConfig { it.copy(plugins = it.plugins + PluginConfig(id, ExternalPluginPath(dir))) }
        fun autoPluginDiscovery(autoPluginDiscovery: Boolean?) = updateConfig { it.copy(autoPluginDiscovery = autoPluginDiscovery) }
    }
}
