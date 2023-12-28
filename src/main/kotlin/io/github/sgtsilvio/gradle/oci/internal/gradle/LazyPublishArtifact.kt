package io.github.sgtsilvio.gradle.oci.internal.gradle

import org.gradle.api.artifacts.ConfigurationPublications
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.property
import java.util.*

/**
 * @author Silvio Giebl
 */
internal class LazyPublishArtifact(objectFactory: ObjectFactory) {
    val file: RegularFileProperty = objectFactory.fileProperty()
    val name = objectFactory.property<String>()
    val classifier = objectFactory.property<String>()
    val extension = objectFactory.property<String>()
    val type: Property<String> = objectFactory.property<String>().convention(extension)
}

private val artifactsThreadLocal = ThreadLocal<LinkedList<LazyPublishArtifact>>()

internal fun ConfigurationPublications.addArtifacts(provider: Provider<out Iterable<LazyPublishArtifact>>) {
    try {
        artifacts(provider.map { artifacts ->
            artifactsThreadLocal.set(artifacts.toCollection(LinkedList()))
            artifacts.map { it.file }
        }) {
            val artifacts = artifactsThreadLocal.get()!!
            val artifact = artifacts.removeFirst()
            if (artifacts.isEmpty()) {
                artifactsThreadLocal.remove()
            }
            name = artifact.name.get()
            classifier = artifact.classifier.get()
            extension = artifact.extension.get()
            type = artifact.type.get()
        }
    } catch (t: Throwable) {
        artifactsThreadLocal.remove()
        throw t
    }
}
