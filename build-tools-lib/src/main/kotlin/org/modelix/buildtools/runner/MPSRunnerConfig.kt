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
) : Serializable {
    fun buildDir() = buildDir ?: workDir()
    fun workDir() = workDir ?: File(".")
}
