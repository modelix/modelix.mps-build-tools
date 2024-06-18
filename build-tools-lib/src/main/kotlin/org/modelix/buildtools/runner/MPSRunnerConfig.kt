package org.modelix.buildtools.runner

import java.io.File
import java.io.Serializable
import java.util.UUID

data class MPSRunnerConfig(
    val mainClassName: String = "Main",
    val mainMethodName: String = "main",
    val additionalModuleDirs: List<File> = emptyList(),
    val additionalModuleDependencies: List<String> = emptyList(),
    /** These directories are searched for modules, and all of them are added as a dependency */
    val additionalModuleDependencyDirs: List<File> = emptyList(),
    val classPathElements: List<File> = emptyList(),
    /** Directories are searched for jar files and added to the classpath */
    val jarFolders: List<File> = emptyList(),
    val mpsHome: File? = null,
    val workDir: File? = null,
    val buildDir: File? = null,
    val jvmArgs: List<String> = emptyList(),
    val moduleId: UUID? = null,
    val plugins: List<PluginConfig> = emptyList(),
    /**
     * Enable or disable automatically loading available plugins.
     * If a value is specified, it will be set as the
     * "autoPluginDiscovery" property [jetbrains.mps.build.ant.MpsLoadTask.setAutoPluginDiscovery]
     * on the "runMPS" task [jetbrains.mps.build.ant.generation.MpsRunnerTask].
     */
    val autoPluginDiscovery: Boolean? = null,
) : Serializable {
    fun buildDir() = buildDir ?: workDir()
    fun workDir() = workDir ?: File(".")
}

/**
 * Specifies a plugin by its [id] and the [path] of its installation.
 */
data class PluginConfig(val id: String, val path: PluginPath) : Serializable

sealed interface PluginPath : Serializable

/**
 * A path to an external plugin directory.
 */
data class ExternalPluginPath(val dir: File) : PluginPath

/**
 * A path to a bundled plugin directory.
 * [dir] has to be a relative path.
 * When the build script is executed it will be resolved against the path
 * specified by the value of the property [MPSRunner.PROPERTY_KEY_ARTIFACTS_MPS].
 */
data class BundledPluginPath(val dir: File) : PluginPath {
    init {
        require(!dir.isAbsolute) {
            "The path `$dir` to a bundled plugin must be a relative path."
        }
    }
}
