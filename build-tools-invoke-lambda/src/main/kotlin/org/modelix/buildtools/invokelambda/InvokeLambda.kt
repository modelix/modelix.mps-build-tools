package org.modelix.buildtools.invokelambda

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * This class is not used directly by the plugin, but copied to the MPS classpath and used to invoke some code that
 * was provided as a Kotlin lambda inside the Gradle script.
 * The runMPS ANT plugin expects some static method and a lambda isn't one.
 */
object InvokeLambda {
    const val PROPERTY_KEY = "runMPS.lambda.file"
    const val RESULT_FILE_NAME = "runMPS-result.obj"

    @JvmStatic
    fun invoke() {
        val lambdaFileName = System.getProperty(PROPERTY_KEY)
        val lambdaFile = File(lambdaFileName)
        val resultFile = lambdaFile.parentFile.resolve(RESULT_FILE_NAME)
        resultFile.delete()
        val lambdaInstance = ObjectInputStream(FileInputStream(lambdaFileName)).use {
            it.readObject() as () -> Any
        }
        val result = lambdaInstance()
        if (result != Unit) {
            ObjectOutputStream(FileOutputStream(resultFile)).use {
                it.writeObject(result)
            }
        }
    }
}