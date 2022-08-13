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
    @get:Optional
    override val filePermissions = objectFactory.property<Int>().value(0b110_100_100)

    @get:Input
    @get:Optional
    override val directoryPermissions = objectFactory.property<Int>().value(0b111_101_101)

    @get:Input
    val permissionPatterns = objectFactory.listProperty<Pair<String, Int?>>()

    @get:Input
    override val userId = objectFactory.property<Long>().convention(0)

    @get:Input
    val userIdPatterns = objectFactory.listProperty<Pair<String, Long>>()

    @get:Input
    override val groupId = objectFactory.property<Long>().convention(0)

    @get:Input
    val groupIdPatterns = objectFactory.listProperty<Pair<String, Long>>()

    @get:Nested
    val children = LinkedList<OciCopySpecImpl>()

    override fun from(source: Any): OciCopySpecImpl {
        sources.from(source)
        return this
    }

    override fun from(source: Any, configureAction: Action<in OciCopySpec>): OciCopySpecImpl {
        return addChild({ it.from(source) }, configureAction)
    }

    override fun into(destinationPath: String): OciCopySpecImpl {
        if (destinationPath.startsWith('/')) {
            throw IllegalArgumentException("destinationPath must not start with '/'")
        }
        if (destinationPath.endsWith('/')) {
            throw IllegalArgumentException("destinationPath must not end with '/'")
        }
        if (destinationPath.contains("//")) {
            throw IllegalArgumentException("destinationPath must not contain '//'")
        }
        this.destinationPath.set(if (destinationPath == "") "" else "$destinationPath/")
        return this
    }

    override fun into(destinationPath: String, configureAction: Action<in OciCopySpec>): OciCopySpecImpl {
        return addChild({ it.into(destinationPath) }, configureAction)
    }

    private inline fun addChild(
        action: (OciCopySpecImpl) -> Unit, userAction: Action<in OciCopySpec>
    ): OciCopySpecImpl {
        val child = objectFactory.newInstance<OciCopySpecImpl>()
        action.invoke(child) // invoke the action before adding the child as the action performs validations
        children.add(child)
        child.filePermissions.set(filePermissions)
        child.directoryPermissions.set(directoryPermissions)
        child.userId.convention(userId)
        child.groupId.convention(groupId)
        userAction.execute(child)
        return child
    }

    override fun rename(directoryPathPattern: String, fileNameRegex: String, replacement: String): OciCopySpecImpl {
        if (directoryPathPattern.startsWith('/')) {
            throw IllegalArgumentException("directoryPathPattern must not start with '/'")
        }
        if (directoryPathPattern != "" && !directoryPathPattern.endsWith('/') && !directoryPathPattern.endsWith("**")) {
            throw IllegalArgumentException("directoryPathPattern must match a directory ('', end with '/' or '**')")
        }
        if (directoryPathPattern.contains("//")) {
            throw IllegalArgumentException("directoryPathPattern must not contain '//'")
        }
        if (fileNameRegex.indexOf('/') in 0..fileNameRegex.length - 2) {
            throw IllegalArgumentException("fileNameRegex must not contain '/' except at the end")
        }
        if (!fileNameRegex.endsWith('/') && replacement.contains('/')) {
            throw IllegalArgumentException("replacement must not contain '/' if fileNameRegex does not match a directory")
        }
        renamePatterns.add(Triple(directoryPathPattern, fileNameRegex, replacement))
        return this
    }

    override fun permissions(pathPattern: String, permissions: Int?): OciCopySpecImpl {
        if (pathPattern.startsWith('/')) {
            throw IllegalArgumentException("pathPattern must not start with '/'")
        }
        permissionPatterns.add(Pair(pathPattern, permissions))
        return this
    }

    override fun userId(pathPattern: String, userId: Long): OciCopySpecImpl {
        if (pathPattern.startsWith('/')) {
            throw IllegalArgumentException("pathPattern must not start with '/'")
        }
        userIdPatterns.add(Pair(pathPattern, userId))
        return this
    }

    override fun groupId(pathPattern: String, groupId: Long): OciCopySpecImpl {
        if (pathPattern.startsWith('/')) {
            throw IllegalArgumentException("pathPattern must not start with '/'")
        }
        groupIdPatterns.add(Pair(pathPattern, groupId))
        return this
    }
}