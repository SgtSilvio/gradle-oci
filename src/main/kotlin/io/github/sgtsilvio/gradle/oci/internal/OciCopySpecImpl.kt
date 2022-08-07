package io.github.sgtsilvio.gradle.oci.internal

import io.github.sgtsilvio.gradle.oci.OciCopySpec
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
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
    val sources = objectFactory.fileCollection()

    @get:Input
    val destinationPath = objectFactory.property<String>().convention("")

    @get:Input
    @get:Optional
    val renamePatterns = objectFactory.listProperty<Triple<String, String, String>>()

    @get:Input
    @get:Optional
    override val filePermissions = objectFactory.property<Int>().value(0b110_100_100)

    @get:Input
    @get:Optional
    override val directoryPermissions = objectFactory.property<Int>().value(0b111_101_101)

    @get:Input
    @get:Optional
    val permissionPatterns = objectFactory.listProperty<Pair<String, Int?>>()

    @get:Input
    override val userId = objectFactory.property<Long>().convention(0)

    @get:Input
    @get:Optional
    val userIdPatterns = objectFactory.listProperty<Pair<String, Long>>()

    @get:Input
    override val groupId = objectFactory.property<Long>().convention(0)

    @get:Input
    @get:Optional
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
        // TODO validation
        this.destinationPath.set("$destinationPath/")
        return this
    }

    override fun into(destinationPath: String, configureAction: Action<in OciCopySpec>): OciCopySpecImpl {
        // TODO validation
        return addChild({ it.into(destinationPath) }, configureAction)
    }

    private inline fun addChild(
        action: (OciCopySpecImpl) -> Unit,
        userAction: Action<in OciCopySpec>
    ): OciCopySpecImpl {
        val child = objectFactory.newInstance<OciCopySpecImpl>()
        children.add(child)
        child.filePermissions.set(filePermissions)
        child.directoryPermissions.set(directoryPermissions)
        child.userId.convention(userId)
        child.groupId.convention(groupId)
        action.invoke(child)
        userAction.execute(child)
        return child
    }

    override fun rename(directoryPathPattern: String, fileNameRegex: String, replacement: String): OciCopySpecImpl {
        // TODO validation
        renamePatterns.add(Triple(directoryPathPattern, fileNameRegex, replacement))
        return this
    }

    override fun permissions(pathPattern: String, permissions: Int?): OciCopySpecImpl {
        // TODO validation
        permissionPatterns.add(Pair(pathPattern, permissions))
        return this
    }

    override fun userId(pathPattern: String, userId: Long): OciCopySpecImpl {
        // TODO validation
        userIdPatterns.add(Pair(pathPattern, userId))
        return this
    }

    override fun groupId(pathPattern: String, groupId: Long): OciCopySpecImpl {
        // TODO validation
        groupIdPatterns.add(Pair(pathPattern, groupId))
        return this
    }
}