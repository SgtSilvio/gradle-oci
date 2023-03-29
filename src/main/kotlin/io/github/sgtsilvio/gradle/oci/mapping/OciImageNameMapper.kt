package io.github.sgtsilvio.gradle.oci.mapping

/**
 * @author Silvio Giebl
 */
class OciImageNameMapper(
    private val groupMappings: Map<String, String>,
    private val moduleMappings: Map<Pair<String, String>, Pair<String, String>>,
    private val moduleVersionMappings: Map<Triple<String, String, String>, Triple<String, String, String>>,
) {

    fun map(group: String, name: String, version: String): OciImageName {
        moduleVersionMappings[Triple(group, name, version)]?.let { (namespace, name, tag) ->
            return OciImageName(namespace, name, tag)
        }
        moduleMappings[Pair(group, name)]?.let { (namespace, name) ->
            return OciImageName(namespace, name, version)
        }
        groupMappings[group]?.let { namespace ->
            return OciImageName(namespace, name, version)
        }
        return OciImageName(mapGroupToImageNamespace(group), name, version)
    }

    private fun mapGroupToImageNamespace(group: String): String {
        val tldEndIndex = group.indexOf('.')
        return if (tldEndIndex == -1) {
            group
        } else {
            group.substring(tldEndIndex + 1).replace('.', '/')
        }
    }
}

fun OciImageNameMapping.createMapper() =
    OciImageNameMapper(groupMappings.get(), moduleMappings.get(), moduleVersionMappings.get())