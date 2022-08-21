package io.github.sgtsilvio.gradle.oci

import org.gradle.api.Action
import org.gradle.api.provider.Property

/**
 * Set of specifications for copying files inspired by [org.gradle.api.file.CopySpec].
 *
 * The differences to [org.gradle.api.file.CopySpec] are:
 * - Ownership (user and group ids) can be set (important for OCI layer tar archives)
 * - Renaming, permissions and ownership rules can be set for specific path patterns
 * - Movement, permissions and ownership rules can be set for directories and not only regular files
 * - All parts are considered as inputs for robust up-to-date checks
 *
 * @author Silvio Giebl
 */
interface OciCopySpec {

    /**
     * Add source files to the current copy spec.
     *
     * @param source see [org.gradle.api.Project.files] for all possible types
     * @return the current copy spec
     */
    fun from(source: Any): OciCopySpec

    /**
     * Add source files to a new child copy spec.
     * The child copy spec is further configured by the given action.
     *
     * A child copy spec inherits renaming rules, permissions and ownership from the current (parent) copy spec, but
     * creates a new scope so that any configuration does not affect the parent copy spec.
     *
     * @param source see [org.gradle.api.Project.files] for all possible types
     * @param action action invoked with the created child copy spec
     * @return the created child copy spec
     */
    fun from(source: Any, action: Action<in OciCopySpec>): OciCopySpec

    /**
     * Set the destination path of the current copy spec.
     *
     * @param destinationPath must not start with `/`, must not end with `/`
     * @return the current copy spec
     */
    fun into(destinationPath: String): OciCopySpec

    /**
     * Set the destination path of a new child copy spec.
     * The child copy spec is further configured by the given action.
     *
     * A child copy spec inherits renaming rules, permissions and ownership from the current (parent) copy spec, but
     * creates a new scope so that any configuration does not affect the parent copy spec.
     *
     * @param destinationPath must not start with `/`, must not end with `/`
     * @param action          action invoked with the created child copy spec
     * @return the created child copy spec
     */
    fun into(destinationPath: String, configureAction: Action<in OciCopySpec>): OciCopySpec

    /**
     * Add a file renaming rule to the current copy spec.
     * The renaming rule is applied to every file.
     * Multiple renaming rules of this copy spec are applied in the supplied order.
     * All renaming rules of parent copy specs are applied before this copy spec's renaming rules.
     *
     * @param fileNameRegex regex applied to the file name without the parent directory path,
     * @param replacement   regex replacement expression that can include substitutions,
     *                      the result must not be empty and must not contain `/`
     * @return the current copy spec
     */
    fun rename(fileNameRegex: String, replacement: String) = rename("**/", fileNameRegex, replacement)

    /**
     * Add a file renaming rule to the current copy spec.
     * The renaming rule is only applied to files whose parent directory path (after all directory movement rules have
     * been applied) match the `parentPathPattern`.
     * Multiple renaming rules of this copy spec are applied in the supplied order.
     * All renaming rules of parent copy specs are applied before this copy spec's renaming rules.
     *
     * @param parentPathPattern glob pattern applied to the parent directory path without the file name,
     *                          must not start with `/` (would never match),
     *                          must match a directory (be empty, end with `/` or '**')
     * @param fileNameRegex     regex applied to the file name without the parent directory path,
     * @param replacement       regex replacement expression that can include substitutions,
     *                          the result must not be empty and must not contain `/`
     * @return the current copy spec
     */
    fun rename(parentPathPattern: String, fileNameRegex: String, replacement: String): OciCopySpec

    /**
     * Add a directory movement rule to the current copy spec.
     * The movement rule is applied to every directory.
     * Multiple movement rules of this copy spec are applied in the supplied order.
     * All movement rules of parent copy specs are applied before this copy spec's movement rules.
     *
     * @param directoryNameRegex regex applied to the directory name without the parent directory path,
     * @param replacement        regex replacement expression that can include substitutions,
     *                           the result may be empty (deleting the directory, moving all children to the parent directory),
     *                           the result may contain multiple '/' (adding directories) but not at the start or end,
     * @return the current copy spec
     */
    fun move(directoryNameRegex: String, replacement: String) = move("**/", directoryNameRegex, replacement)

    /**
     * Add a renaming rule to the current copy spec.
     * The renaming rule is only applied to files/directories that match the directoryPathPattern.
     *
     * @param parentPathPattern  glob pattern applied to the parent directory path without the directory name,
     *                           must not start with `/` (would never match),
     *                           must match a directory (be empty, end with `/` or '**')
     * @param directoryNameRegex regex applied to the directory name without the parent directory path,
     * @param replacement        regex replacement expression that can include substitutions,
     *                           the result may be empty (deleting the directory, moving all children to the parent directory),
     *                           the result may contain multiple '/' (adding directories) but not at the start or end,
     * @return the current copy spec
     */
    fun move(parentPathPattern: String, directoryNameRegex: String, replacement: String): OciCopySpec

    /**
     * Default (UNIX) permissions for files created at the destination.
     * If no value is present, the value is inherited from the parent copy spec if there is one in the used scope.
     * If no value is present and this is a root copy spec in the used scope, it defaults to `0b111_101_101`/`0755`.
     * The default value is used unless a specific permission supplied via [permissions] matches.
     */
    val filePermissions: Property<Int>

    /**
     * Default (UNIX) permissions for directories created at the destination.
     * If no value is present, the value is inherited from the parent copy spec if there is one in the used scope.
     * If no value is present and this is a root copy spec in the used scope, it defaults to `0b110_100_100`/`0644`.
     * The default value is used unless a specific permission supplied via [permissions] matches.
     */
    val directoryPermissions: Property<Int>

    /**
     * Add a (UNIX) permission rule to the current copy spec.
     * The permission rule is only applied to files and directories whose relative destination path (after all directory
     * move and file renaming rules have been applied) match the `pathPattern`.
     *
     * @param pathPattern glob pattern applied to the relative destination path of a file or directory,
     *                    must not start with `/` (would never match),
     *                    may end with `/` (then matching a directory)
     * @param permissions UNIX permissions, from `0000` to `0777`
     * @return the current copy spec
     */
    fun permissions(pathPattern: String, permissions: Int): OciCopySpec

    /**
     * Default user id ownership for files and directories created at the destination.
     * If no value is present, the value is inherited from the parent copy spec if there is one in the used scope.
     * If no value is present and this is a root copy spec in the used scope, it defaults to `0`.
     * The default value is used unless a specific user id supplied via [userId] matches.
     */
    val userId: Property<Long>

    /**
     * Add a user id ownership rule to the current copy spec.
     * The user id ownership rule is only applied to files and directories whose relative destination path (after all
     * directory movement and file renaming rules have been applied) match the `pathPattern`.
     *
     * @param pathPattern glob pattern applied to the relative destination path of a file or directory,
     *                    must not start with `/` (would never match),
     *                    may end with `/` (then matching a directory)
     * @param userId      user id number
     * @return the current copy spec
     */
    fun userId(pathPattern: String, userId: Long): OciCopySpec

    /**
     * Default group id ownership for files and directories created at the destination.
     * If no value is present, the value is inherited from the parent copy spec if there is one in the used scope.
     * If no value is present and this is a root copy spec in the used scope, it defaults to `0`.
     * The default value is used unless a specific group id supplied via [groupId] matches.
     */
    val groupId: Property<Long>

    /**
     * Add a group id ownership rule to the current copy spec.
     * The group id ownership rule is only applied to files and directories whose relative destination path (after all
     * directory movement and file renaming rules have been applied) match the `pathPattern`.
     *
     * @param pathPattern glob pattern applied to the relative destination path of a file or directory,
     *                    must not start with `/` (would never match),
     *                    may end with `/` (then matching a directory)
     * @param groupId     group id number
     * @return the current copy spec
     */
    fun groupId(pathPattern: String, groupId: Long): OciCopySpec
}