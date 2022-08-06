package io.github.sgtsilvio.gradle.oci

import org.gradle.api.Action
import org.gradle.api.provider.Property

/**
 * @author Silvio Giebl
 */
interface OciCopySpec {

    fun from(source: Any): OciCopySpec

    fun from(source: Any, configureAction: Action<in OciCopySpec>): OciCopySpec

    fun into(destinationPath: String): OciCopySpec
    // TODO validations:
    //  destinationPath: must not start with /, must not end with /

    fun into(destinationPath: String, configureAction: Action<in OciCopySpec>): OciCopySpec
    // TODO validations:
    //  destinationPath: must not start with /, must not end with /

    fun rename(fileNameRegex: String, replacement: String): OciCopySpec {
        return rename("**/", fileNameRegex, replacement)
    }
    // TODO validations:
    //  fileNameRegex: must not contain / except at the end
    //  replacement: must not contain /
    //   ??? if fileNameRegex ends with /, then replacement may be empty or may contain slashes ???
    //   runtime validation: must not be empty if fileNameRegex does not end with /
    // TODO delegates to rename by setting directoryPathPattern to **/

    fun rename(directoryPathPattern: String, fileNameRegex: String, replacement: String): OciCopySpec
    // TODO validations:
    //  directoryPathPattern: must not start with /, must end with /
    //  fileNameRegex: must not contain / except at the end
    //  replacement: must not contain /
    //   ??? if fileNameRegex ends with /, then replacement may be empty or may contain slashes ???
    //   runtime validation: must not be empty if fileNameRegex does not end with /

    /**
     * Default permissions for files created at the destination.
     * Initially set to `0b111_101_101`/`0755` for root copy specs, otherwise to the same value as the parent copy spec.
     * Setting it to `null` means that the actual permissions of the source file are used also at the destination.
     * The default value is used unless a specific permission supplied via [permissions] matches.
     */
    val filePermissions: Property<Int>

    /**
     * Default permissions for directories created at the destination.
     * Initially set to `0b110_100_100`/`0644` for root copy specs, otherwise to the same value as the parent copy spec.
     * Setting it to `null` means that the actual permissions of the source directory are used also at the destination.
     * The default value is used unless a specific permission supplied via [permissions] matches.
     */
    val directoryPermissions: Property<Int>

    fun permissions(pathPattern: String, permissions: Int?): OciCopySpec
    // TODO validations:
    //  pathPattern: must not start with /, may end with /

    /**
     * Default user id for files and directories created at the destination.
     * Initially set to `0` for root copy specs, otherwise to the same value as the parent copy spec.
     * The default value is used unless a specific user id supplied via [userId] matches.
     */
    val userId: Property<Long>

    fun userId(pathPattern: String, userId: Long): OciCopySpec
    // TODO validations:
    //  pathPattern: must not start with /, may end with /

    /**
     * Default group id for files and directories created at the destination.
     * Initially set to `0` for root copy specs, otherwise to the same value as the parent copy spec.
     * The default value is used unless a specific group id supplied via [groupId] matches.
     */
    val groupId: Property<Long>

    fun groupId(pathPattern: String, groupId: Long): OciCopySpec
    // TODO validations:
    //  pathPattern: must not start with /, may end with /
}