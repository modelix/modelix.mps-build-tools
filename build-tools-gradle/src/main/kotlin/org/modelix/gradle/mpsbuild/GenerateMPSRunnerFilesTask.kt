package org.modelix.gradle.mpsbuild

import org.gradle.api.DefaultTask
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import org.modelix.buildtools.runner.MPSRunner
import org.modelix.buildtools.runner.MPSRunnerConfig
import javax.inject.Inject

@CacheableTask
abstract class GenerateMPSRunnerFilesTask @Inject constructor(of: ObjectFactory) : DefaultTask() {
    @Input
    val config: Property<MPSRunnerConfig> = of.property<MPSRunnerConfig>()

    @TaskAction
    fun runMPS() {
        val config = config.get()
        config.buildDir().mkdirs()
        val runner = MPSRunner(config)
        runner.generateAll()
    }
}
