package io.github.sgtsilvio.gradle.oci.component

import java.io.Serializable

/**
 * @author Silvio Giebl
 */
data class ComponentId(val moduleId: ModuleId, val version: String) : Comparable<ComponentId>, Serializable {
    override fun compareTo(other: ComponentId): Int {
        moduleId.compareTo(other.moduleId).also { if (it != 0) return it }
        return version.compareTo(other.version)
    }
}

data class ModuleId(val group: String, val name: String) : Comparable<ModuleId>, Serializable {
    override fun compareTo(other: ModuleId): Int {
        group.compareTo(other.group).also { if (it != 0) return it }
        return name.compareTo(other.name)
    }
}
