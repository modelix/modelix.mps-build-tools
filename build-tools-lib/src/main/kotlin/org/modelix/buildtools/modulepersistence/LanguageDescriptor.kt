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
package org.modelix.buildtools.modulepersistence

import org.modelix.buildtools.ModuleIdAndName
import org.modelix.buildtools.childElements
import org.w3c.dom.Element

class LanguageDescriptor(xml: Element) : ModuleDescriptor(xml) {
    val languageVersion: Int
    val extendedLanguages: Set<ModuleIdAndName>
    private val accessoryModels: List<String>
    val generators: List<GeneratorDescriptor>

    init {
        // see LanguageDescriptorPersistence in MPS
        languageVersion = xml.getAttribute("languageVersion").toIntOrNull()
            ?: xml.getAttribute("version").toIntOrNull()
            ?: 0

        extendedLanguages = xml.childElements("extendedLanguages")
            .flatMap { it.childElements("extendedLanguage") }
            .map { ModuleIdAndName.fromReference(it.textContent) }
            .toSet()

        // TODO deserialize model reference
        accessoryModels = (xml.childElements("accessoryModels") + xml.childElements("library"))
            .flatMap { it.childElements("model") }
            .map { it.getAttribute("modelUID") }

        generators = xml.childElements("generators").flatMap { it.childElements("generator") }
            .map { GeneratorDescriptor(it, this) }
    }
}
