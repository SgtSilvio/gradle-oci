package io.github.sgtsilvio.gradle.oci.internal

import io.github.sgtsilvio.gradle.oci.OciCopySpec
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.*
import org.gradle.api.tasks.Optional
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.property
import java.util.*
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
open class OciCopySpecImpl @Inject constructor(private val objectFactory: ObjectFactory) : OciCopySpec {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val sources = objectFactory.fileCollection()

    @get:Input
    val destinationPath = objectFactory.property<String>().convention("")

    @get:Input
    val renamePatterns = objectFactory.listProperty<Triple<String, String, String>>()

    @get:Input
    val movePatterns = objectFactory.listProperty<Triple<String, String, String>>()

    @get:Input
    @get:Optional
    override val filePermissions = objectFactory.property<Int>()

    @get:Input
    @get:Optional
    override val directoryPermissions = objectFactory.property<Int>()

    @get:Input
    val permissionPatterns = objectFactory.listProperty<Pair<String, Int>>()

    @get:Input
    @get:Optional
    override val userId = objectFactory.property<Long>()

    @get:Input
    val userIdPatterns = objectFactory.listProperty<Pair<String, Long>>()

    @get:Input
    @get:Optional
    override val groupId = objectFactory.property<Long>()

    @get:Input
    val groupIdPatterns = objectFactory.listProperty<Pair<String, Long>>()

    @get:Nested
    val children = LinkedList<OciCopySpecImpl>()

    override fun from(source: Any): OciCopySpecImpl {
        sources.from(source)
        return this
    }

    override fun from(source: Any, action: Action<in OciCopySpec>): OciCopySpecImpl {
        return addChild({ it.from(source) }, action)
    }

    override fun into(destinationPath: String): OciCopySpecImpl {
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

    override fun into(destinationPath: String, action: Action<in OciCopySpec>): OciCopySpecImpl {
        return addChild({ it.into(destinationPath) }, action)
    }

    private inline fun addChild(
        initAction: (OciCopySpecImpl) -> Unit,
        userAction: Action<in OciCopySpec>,
    ): OciCopySpecImpl {
        val child = objectFactory.newInstance<OciCopySpecImpl>()
        initAction.invoke(child) // invoke the action before adding the child as the action performs validations
        children.add(child)
        userAction.execute(child)
        return child
    }

    override fun rename(parentPathPattern: String, fileNameRegex: String, replacement: String): OciCopySpecImpl {
        if (parentPathPattern.contains("//")) {
            throw IllegalArgumentException("parentPathPattern must not contain '//'")
        }
        if (parentPathPattern.startsWith('/')) {
            throw IllegalArgumentException("parentPathPattern must not start with '/'")
        }
        if (parentPathPattern != "" && !parentPathPattern.endsWith('/') && !parentPathPattern.endsWith("**")) {
            throw IllegalArgumentException("parentPathPattern must match a directory ('', end with '/' or '**')")
        }
        renamePatterns.add(Triple(parentPathPattern, fileNameRegex, replacement))
        return this
    }

    override fun move(parentPathPattern: String, directoryNameRegex: String, replacement: String): OciCopySpec {
        if (parentPathPattern.contains("//")) {
            throw IllegalArgumentException("parentPathPattern must not contain '//'")
        }
        if (parentPathPattern.startsWith('/')) {
            throw IllegalArgumentException("parentPathPattern must not start with '/'")
        }
        if (parentPathPattern != "" && !parentPathPattern.endsWith('/') && !parentPathPattern.endsWith("**")) {
            throw IllegalArgumentException("parentPathPattern must match a directory ('', end with '/' or '**')")
        }
        movePatterns.add(Triple(parentPathPattern, directoryNameRegex, replacement))
        return this
    }

    override fun permissions(pathPattern: String, permissions: Int): OciCopySpecImpl {
        if (pathPattern.contains("//")) {
            throw IllegalArgumentException("pathPattern must not contain '//'")
        }
        if (pathPattern.startsWith('/')) {
            throw IllegalArgumentException("pathPattern must not start with '/'")
        }
        permissionPatterns.add(Pair(pathPattern, permissions))
        return this
    }

    override fun userId(pathPattern: String, userId: Long): OciCopySpecImpl {
        if (pathPattern.contains("//")) {
            throw IllegalArgumentException("pathPattern must not contain '//'")
        }
        if (pathPattern.startsWith('/')) {
            throw IllegalArgumentException("pathPattern must not start with '/'")
        }
        userIdPatterns.add(Pair(pathPattern, userId))
        return this
    }

    override fun groupId(pathPattern: String, groupId: Long): OciCopySpecImpl {
        if (pathPattern.contains("//")) {
            throw IllegalArgumentException("pathPattern must not contain '//'")
        }
        if (pathPattern.startsWith('/')) {
            throw IllegalArgumentException("pathPattern must not start with '/'")
        }
        groupIdPatterns.add(Pair(pathPattern, groupId))
        return this
    }
}