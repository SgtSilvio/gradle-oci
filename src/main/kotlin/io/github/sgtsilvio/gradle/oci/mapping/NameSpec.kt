package io.github.sgtsilvio.gradle.oci.mapping

sealed interface NameSpec {
    operator fun plus(string: String): NameSpec = plus(StringNameSpec(string))

    operator fun plus(nameSpec: NameSpec): NameSpec = CompoundNameSpec(
        when (nameSpec) {
            is CompoundNameSpec -> arrayOf(this, *nameSpec.parts)
            else -> arrayOf(this, nameSpec)
        }
    )

    fun prefix(string: String): NameSpec = prefix(StringNameSpec(string))

    fun prefix(nameSpec: NameSpec): NameSpec = PreOrPostfixNameSpec(this, nameSpec, true)

    fun postfix(string: String): NameSpec = postfix(StringNameSpec(string))

    fun postfix(nameSpec: NameSpec): NameSpec = PreOrPostfixNameSpec(this, nameSpec, false)

    fun generateName(parameters: Map<String, String>): String
}

class StringNameSpec(private val value: String) : NameSpec {
    override fun generateName(parameters: Map<String, String>) = value
}

class ParameterNameSpec(private val key: String, private val defaultValue: String?) : NameSpec {
    override fun generateName(parameters: Map<String, String>) =
        parameters[key] ?: defaultValue ?: throw IllegalStateException("required parameter '$key' is missing")
}

private class CompoundNameSpec(val parts: Array<NameSpec>) : NameSpec {
    override fun plus(nameSpec: NameSpec) = CompoundNameSpec(
        when (nameSpec) {
            is CompoundNameSpec -> arrayOf(*parts, *nameSpec.parts)
            else -> arrayOf(*parts, nameSpec)
        }
    )

    override fun generateName(parameters: Map<String, String>) = buildString {
        for (part in parts) {
            append(part.generateName(parameters))
        }
    }
}

private class PreOrPostfixNameSpec(
    private val main: NameSpec,
    private val preOrPostfix: NameSpec,
    private val isPrefix: Boolean,
) : NameSpec {
    override fun generateName(parameters: Map<String, String>): String {
        val name = main.generateName(parameters)
        return if (name.isEmpty()) "" else {
            val preOrPostfix = preOrPostfix.generateName(parameters)
            if (isPrefix) preOrPostfix + name else name + preOrPostfix
        }
    }
}
