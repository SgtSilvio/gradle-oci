package io.github.sgtsilvio.gradle.oci.mapping

sealed interface NameSpec {
    operator fun plus(string: String): NameSpec = plus(StringNameSpec(string))

    operator fun plus(nameSpec: NameSpec): NameSpec = CompoundNameSpec(this, nameSpec)

    fun prefix(string: String): NameSpec = prefix(StringNameSpec(string))

    fun prefix(nameSpec: NameSpec): NameSpec = PreOrPostfixNameSpec(this, nameSpec, true)

    fun postfix(string: String): NameSpec = postfix(StringNameSpec(string))

    fun postfix(nameSpec: NameSpec): NameSpec = PreOrPostfixNameSpec(this, nameSpec, false)

    fun generateName(parameters: Map<String, String>): String
}

class StringNameSpec(private val string: String) : NameSpec {
    override fun generateName(parameters: Map<String, String>) = string
}

class ParameterNameSpec(private val key: String, private val defaultValue: String?) : NameSpec {
    override fun generateName(parameters: Map<String, String>) =
        parameters[key] ?: defaultValue ?: throw IllegalStateException("required parameter '$key' is missing")
}

private class CompoundNameSpec(private val left: NameSpec, private val right: NameSpec) : NameSpec {
    override fun generateName(parameters: Map<String, String>) =
        left.generateName(parameters) + right.generateName(parameters)
}

private class PreOrPostfixNameSpec(
    private val nameSpec: NameSpec,
    private val preOrPostfix: NameSpec,
    private val isPrefix: Boolean,
) : NameSpec {
    override fun generateName(parameters: Map<String, String>): String {
        val name = nameSpec.generateName(parameters)
        return if (name.isEmpty()) "" else {
            val preOrPostfix = preOrPostfix.generateName(parameters)
            if (isPrefix) preOrPostfix + name else name + preOrPostfix
        }
    }
}
