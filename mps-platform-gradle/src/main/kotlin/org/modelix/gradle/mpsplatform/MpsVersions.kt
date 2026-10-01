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
package org.modelix.gradle.mpsplatform

import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import java.io.File

/**
 * The supported MPS versions (major version to the full version used for building and testing).
 * https://artifacts.itemis.cloud/service/rest/repository/browse/maven-mps/com/jetbrains/mps/
 */
val SUPPORTED_MPS_VERSIONS: Map<String, String> = mapOf(
    "2024.1" to "2024.1.1",
    "2024.3" to "2024.3",
    "2025.1" to "2025.1.2",
)

/** Used if neither `mps.version.major` nor `mps.version` is specified. */
const val DEFAULT_MPS_MAJOR_VERSION = "2025.1"

private const val OLDEST_SUPPORTED_MPS_PLATFORM_VERSION = 241

/** Converts an MPS version (e.g. 2024.3.1) to the version of the IntelliJ Platform it's based on (e.g. 243). */
fun String.toMpsPlatformVersion(): Int = replace(Regex("""20(\d\d)\.(\d+).*"""), "$1$2").toInt()

/** The oldest MPS platform version the MPS plugins are compatible with. */
val MPS_PLUGIN_SINCE_BUILD: String = SUPPORTED_MPS_VERSIONS.keys.minOf { it.toMpsPlatformVersion() }.toString()

/** The newest MPS platform version the MPS plugins are compatible with. */
val MPS_PLUGIN_UNTIL_BUILD: String = SUPPORTED_MPS_VERSIONS.keys.maxOf { it.toMpsPlatformVersion() }.toString() + ".*"

/**
 * The major version of MPS (e.g. 2025.1) to build and test with, configured by the property `mps.version.major` or
 * derived from `mps.version`.
 */
val Project.mpsMajorVersion: String get() {
    if (project != rootProject) return rootProject.mpsMajorVersion
    return project.findProperty("mps.version.major")?.toString()?.takeIf { it.isNotEmpty() }
        ?: project.findProperty("mps.version")?.toString()?.takeIf { it.isNotEmpty() }?.replace(Regex("""(20\d\d\.\d+).*"""), "$1")
        ?: DEFAULT_MPS_MAJOR_VERSION
}

/**
 * The full version of MPS (e.g. 2025.1.2) to build and test with, configured by the property `mps.version` or
 * derived from [mpsMajorVersion].
 */
val Project.mpsVersion: String get() {
    if (project != rootProject) return rootProject.mpsVersion
    val version = project.findProperty("mps.version")?.toString()?.takeIf { it.isNotEmpty() }
        ?: mpsMajorVersion.let {
            requireNotNull(SUPPORTED_MPS_VERSIONS[it]) {
                "Unknown MPS version: $it. Supported versions: ${SUPPORTED_MPS_VERSIONS.keys.joinToString()}"
            }
        }
    require(version.toMpsPlatformVersion() >= OLDEST_SUPPORTED_MPS_PLATFORM_VERSION) {
        "MPS $version isn't supported. The oldest supported version is ${SUPPORTED_MPS_VERSIONS.keys.first()}."
    }
    return version
}

/** The version of the IntelliJ Platform MPS is based on (e.g. 251 for MPS 2025.1). */
val Project.mpsPlatformVersion: Int get() = mpsVersion.toMpsPlatformVersion()

/** The Java version required to run MPS. */
val Project.mpsJavaVersion: Int get() = if (mpsPlatformVersion >= 251) 21 else 17

/** The directory into which MPS is extracted by [copyMps]. It's shared by all projects of a build. */
val Project.mpsHomeDir: Provider<Directory> get() {
    if (project != rootProject) return rootProject.mpsHomeDir
    return project.layout.buildDirectory.dir("mps-$mpsVersion")
}

/**
 * The plugins folder of an MPS installation, configured by the property `mps<platform version>.plugins.dir`
 * (e.g. mps251.plugins.dir). Otherwise, the plugins folder in the configuration directory of [mpsMajorVersion] is used
 * on macOS, and as a last resort the property `mps.plugins.dir`, which isn't specific to an MPS version.
 * Returns null if the folder doesn't exist.
 */
val Project.mpsPluginsDir: File? get() {
    val candidates = listOfNotNull(
        project.findProperty("mps$mpsPlatformVersion.plugins.dir")?.toString()?.let { file(it) },
        System.getProperty("user.home")?.let { file(it).resolve("Library/Application Support/JetBrains/MPS$mpsMajorVersion/plugins/") },
        project.findProperty("mps.plugins.dir")?.toString()?.let { file(it) },
    )
    return candidates.firstOrNull { it.isDirectory }
}
