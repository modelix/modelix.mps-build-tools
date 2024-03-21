package org.modelix.buildtools

import java.io.File

class StubsSolutionGenerator(
    val solutionIdAndName: ModuleIdAndName,
    val jarPaths: List<String>,
    val moduleDependencies: List<ModuleIdAndName>,
) {
    fun generateFile(solutionFile: File) {
        solutionFile.parentFile.mkdirs()
        solutionFile.writeText(generateString())
    }

    fun generateString(): String {
        val xml = newXmlDocument {
            newChild("solution") {
                setAttribute("name", solutionIdAndName.name!!)
                setAttribute("pluginKind", "PLUGIN_OTHER")
                setAttribute("moduleVersion", "0")
                setAttribute("uuid", solutionIdAndName.id.toString())
                newChild("facets") {
                    newChild("facet") {
                        setAttribute("type", "java")
                    }
                }
                newChild("models") {
                    for ((folder, paths) in jarPaths.groupBy { it.substringBeforeLast("/", ".") }) {
                        newChild("modelRoot") {
                            setAttribute("type", "java_classes")
                            setAttribute("contentPath", folder)
                            for (file in paths.map { it.substringAfterLast("/") }) {
                                newChild("sourceRoot") {
                                    setAttribute("location", file)
                                }
                            }
                        }
                    }
                }
                newChild("dependencies") {
                    newChild("dependency", "6354ebe7-c22a-4a0f-ac54-50b52ab9b065(JDK)") {
                        setAttribute("reexport", "true")
                    }
                    for (dep in moduleDependencies) {
                        newChild("dependency", dep.toString()) {
                            setAttribute("reexport", "true")
                        }
                    }
                }
                newChild("stubModelEntries") {
                    for (jar in jarPaths) {
                        newChild("stubModelEntry") {
                            setAttribute("path", jar)
                        }
                    }
                }
            }
        }
        return xmlToString(xml)
    }
}
