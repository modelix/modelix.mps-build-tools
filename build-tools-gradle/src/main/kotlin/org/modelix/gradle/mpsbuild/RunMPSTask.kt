package org.modelix.gradle.mpsbuild

import kotlinx.coroutines.CompletableDeferred
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.JavaExec
import org.gradle.kotlin.dsl.property
import org.modelix.buildtools.invokelambda.InvokeLambda
import org.modelix.buildtools.runner.MPSRunnerConfig
import java.io.FileInputStream
import java.io.ObjectInputStream

open class RunMPSTask : JavaExec() {

    @Input
    val config: Property<MPSRunnerConfig> = objectFactory.property<MPSRunnerConfig>()

    @Internal
    var result: CompletableDeferred<Any>? = null

    init {
        mainClass.set("org.apache.tools.ant.launch.Launcher")
        doLast {
            result?.let { deferred ->
                val resultFile = config.get().buildDir().resolve(InvokeLambda.RESULT_FILE_NAME)
                try {
                    check(resultFile.exists()) { "Return value of task $name not found at ${resultFile.absolutePath}" }
                    ObjectInputStream(FileInputStream(resultFile)).use {
                        deferred.complete(it.readObject())
                    }
                } catch (ex: Exception) {
                    deferred.completeExceptionally(ex)
                }
            }
        }
    }
}