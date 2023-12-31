package io.github.sgtsilvio.gradle.oci.internal.copyspec

import io.github.sgtsilvio.gradle.oci.OciCopySpec
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.util.PatternFilterable
import org.gradle.api.tasks.util.PatternSet
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.property
import java.util.*
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
abstract class OciCopySpecImpl @Inject constructor(private val objectFactory: ObjectFactory) : OciCopySpec {

    val sources = objectFactory.fileCollection()
    val destinationPath: Property<String> = objectFactory.property<String>().convention("")
    final override val filter = objectFactory.newInstance<PatternSet>()
    val renamePatterns = mutableListOf<Triple<String, String, String>>()
    val movePatterns = mutableListOf<Triple<String, String, String>>()
    final override val filePermissions = objectFactory.property<Int>()
    final override val directoryPermissions = objectFactory.property<Int>()
    val permissionPatterns = mutableListOf<Pair<String, Int>>()
    final override val userId = objectFactory.property<Long>()
    val userIdPatterns = mutableListOf<Pair<String, Long>>()
    final override val groupId = objectFactory.property<Long>()
    val groupIdPatterns = mutableListOf<Pair<String, Long>>()
    val children = LinkedList<OciCopySpecImpl>()

    final override fun from(source: Any): OciCopySpecImpl {
        sources.from(source)
        return this
    }

    final override fun from(source: Any, action: Action<in OciCopySpec>) = addChild({ from(source) }, action)

    final override fun into(destinationPath: String): OciCopySpecImpl {
        if (destinationPath.contains("//")) {
            throw IllegalArgumentException("destinationPath must not contain '//'")
        }
        if (destinationPath.startsWith('/')) {
            throw IllegalArgumentException("destinationPath must not start with '/'")
        }
        if (destinationPath.endsWith('/')) {
            throw IllegalArgumentException("destinationPath must not end with '/'")
        }
        this.destinationPath.set(destinationPath)
        return this
    }

    final override fun into(destinationPath: String, action: Action<in OciCopySpec>) =
        addChild({ into(destinationPath) }, action)

    private inline fun addChild(init: OciCopySpecImpl.() -> Unit, userAction: Action<in OciCopySpec>): OciCopySpecImpl {
        val child = objectFactory.newInstance<OciCopySpecImpl>()
        child.init() // invoke init before adding the child as it performs validations
        children += child
        userAction.execute(child)
        return child
    }

    final override fun filter(action: Action<in PatternFilterable>) = action.execute(filter)

    final override fun rename(parentPathPattern: String, fileNameRegex: String, replacement: String): OciCopySpecImpl {
        if (parentPathPattern.contains("//")) {
            throw IllegalArgumentException("parentPathPattern must not contain '//'")
        }
        if (parentPathPattern.startsWith('/')) {
            throw IllegalArgumentException("parentPathPattern must not start with '/'")
        }
        if ((parentPathPattern != "") && !parentPathPattern.endsWith('/') && !parentPathPattern.endsWith("**")) {
            throw IllegalArgumentException("parentPathPattern must match a directory ('', end with '/' or '**')")
        }
        renamePatterns += Triple(parentPathPattern, fileNameRegex, replacement)
        return this
    }

    final override fun move(
        parentPathPattern: String,
        directoryNameRegex: String,
        replacement: String,
    ): OciCopySpecImpl {
        if (parentPathPattern.contains("//")) {
            throw IllegalArgumentException("parentPathPattern must not contain '//'")
        }
        if (parentPathPattern.startsWith('/')) {
            throw IllegalArgumentException("parentPathPattern must not start with '/'")
        }
        if ((parentPathPattern != "") && !parentPathPattern.endsWith('/') && !parentPathPattern.endsWith("**")) {
            throw IllegalArgumentException("parentPathPattern must match a directory ('', end with '/' or '**')")
        }
        movePatterns += Triple(parentPathPattern, directoryNameRegex, replacement)
        return this
    }

    final override fun permissions(pathPattern: String, permissions: Int): OciCopySpecImpl {
        if (pathPattern.contains("//")) {
            throw IllegalArgumentException("pathPattern must not contain '//'")
        }
        if (pathPattern.startsWith('/')) {
            throw IllegalArgumentException("pathPattern must not start with '/'")
        }
        permissionPatterns += Pair(pathPattern, permissions)
        return this
    }

    final override fun userId(pathPattern: String, userId: Long): OciCopySpecImpl {
        if (pathPattern.contains("//")) {
            throw IllegalArgumentException("pathPattern must not contain '//'")
        }
        if (pathPattern.startsWith('/')) {
            throw IllegalArgumentException("pathPattern must not start with '/'")
        }
        userIdPatterns += Pair(pathPattern, userId)
        return this
    }

    final override fun groupId(pathPattern: String, groupId: Long): OciCopySpecImpl {
        if (pathPattern.contains("//")) {
            throw IllegalArgumentException("pathPattern must not contain '//'")
        }
        if (pathPattern.startsWith('/')) {
            throw IllegalArgumentException("pathPattern must not start with '/'")
        }
        groupIdPatterns += Pair(pathPattern, groupId)
        return this
    }

    fun asInput(providerFactory: ProviderFactory): Provider<OciCopySpecInput> {
        val lazy = lazy { OciCopySpecInput(this, null) }
        return providerFactory.provider { lazy.value }
    }
}
