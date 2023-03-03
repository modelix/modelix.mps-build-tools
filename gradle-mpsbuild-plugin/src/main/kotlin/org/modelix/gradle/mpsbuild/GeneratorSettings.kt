package org.modelix.gradle.mpsbuild

import org.modelix.buildtools.Macros
import java.io.File
import java.io.Serializable

data class GeneratorSettings(
    val mpsPath: String?,
    val modulePaths: List<String>,
    val heapSize: String,
    val publications: List<PublicationSettings>,
    val macros: Macros,
    val buildDir: File
) : Serializable