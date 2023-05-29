package io.github.sgtsilvio.gradle.oci.mapping

import io.github.sgtsilvio.gradle.oci.internal.json.*

fun NameSpec.encodeToJsonString() = when (this) {
    is StringNameSpec -> jsonString(value)
    is CompoundNameSpec -> jsonArray { encodeCompoundNameSpec(this@encodeToJsonString) }
    is ParameterNameSpec -> jsonObject { encodeParameterNameSpec(this@encodeToJsonString) }
    is PreOrPostfixNameSpec -> jsonObject { encodePreOrPostfixNameSpec(this@encodeToJsonString) }
}

fun JsonObjectStringBuilder.addNameSpec(key: String, nameSpec: NameSpec) = when (nameSpec) {
    is StringNameSpec -> addString(key, nameSpec.value)
    is CompoundNameSpec -> addArray(key) { encodeCompoundNameSpec(nameSpec) }
    is ParameterNameSpec -> addObject(key) { encodeParameterNameSpec(nameSpec) }
    is PreOrPostfixNameSpec -> addObject(key) { encodePreOrPostfixNameSpec(nameSpec) }
}

private fun JsonArrayStringBuilder.addNameSpec(nameSpec: NameSpec) = when (nameSpec) {
    is StringNameSpec -> addString(nameSpec.value)
    is CompoundNameSpec -> addArray { encodeCompoundNameSpec(nameSpec) }
    is ParameterNameSpec -> addObject { encodeParameterNameSpec(nameSpec) }
    is PreOrPostfixNameSpec -> addObject { encodePreOrPostfixNameSpec(nameSpec) }
}

private fun JsonArrayStringBuilder.encodeCompoundNameSpec(compoundNameSpec: CompoundNameSpec) {
    for (part in compoundNameSpec.parts) {
        addNameSpec(part)
    }
}

private fun JsonObjectStringBuilder.encodeParameterNameSpec(parameterNameSpec: ParameterNameSpec) {
    addString("parameter", parameterNameSpec.key)
    addStringIfNotNull("defaultValue", parameterNameSpec.defaultValue)
}

private fun JsonObjectStringBuilder.encodePreOrPostfixNameSpec(preOrPostfixNameSpec: PreOrPostfixNameSpec) {
    addNameSpec("main", preOrPostfixNameSpec.main)
    addNameSpec(if (preOrPostfixNameSpec.isPrefix) "prefix" else "postfix", preOrPostfixNameSpec.preOrPostfix)
}

fun String.decodeAsJsonToNameSpec() = jsonValue(this).decodeNameSpec()

fun JsonValue.decodeNameSpec(): NameSpec = when {
    isString() -> StringNameSpec(asString())
    isArray() -> decodeCompoundNameSpec()
    isObject() -> asObject().run {
        when {
            hasKey("parameter") -> decodeParameterNameSpec()
            hasKey("prefix") -> decodePreOrPostfixNameSpec(true)
            hasKey("postfix") -> decodePreOrPostfixNameSpec(false)
            else -> throw JsonException.create("parameter|prefix|postfix", "required, but none found")
        }
    }
    else -> throw JsonException.create("", "must be a string, object, or array, but is '$this'")
}

private fun JsonValue.decodeCompoundNameSpec() = CompoundNameSpec(asArray().toList { decodeNameSpec() }.toTypedArray())

private fun JsonObject.decodeParameterNameSpec() =
    ParameterNameSpec(getString("parameter"), getStringOrNull("defaultValue"))

private fun JsonObject.decodePreOrPostfixNameSpec(isPrefix: Boolean) = PreOrPostfixNameSpec(
    get("main") { decodeNameSpec() },
    get(if (isPrefix) "prefix" else "postfix") { decodeNameSpec() },
    isPrefix,
)
