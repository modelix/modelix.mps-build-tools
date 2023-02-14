package org.modelix.gradle.mpsbuild

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.modelix.buildtools.BuildScriptGenerator
import javax.inject.Inject

abstract class GenerateAntScript @Inject constructor(of: ObjectFactory): DefaultTask() {

    @Input
    val generator: Property<BuildScriptGenerator> = of.property(BuildScriptGenerator::class.java)

    @OutputFile
    val antFile: RegularFileProperty = of.fileProperty()

    @TaskAction
    fun generate() {
        val generator = generator.get()
        val xml = generator.generateXML()
        antFile.asFile.get().writeText(xml)
    }
}