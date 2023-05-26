package io.github.sgtsilvio.gradle.oci.mapping

import io.github.sgtsilvio.gradle.oci.internal.json.*

fun NameSpec.encodeToJsonString() = jsonObject { encodeNameSpec(this@encodeToJsonString) }

private fun JsonObjectStringBuilder.encodeNameSpec(nameSpec: NameSpec) = when (nameSpec) {
    is StringNameSpec -> encodeStringNameSpec(nameSpec)
    is ParameterNameSpec -> encodeParameterNameSpec(nameSpec)
    is CompoundNameSpec -> encodeCompoundNameSpec(nameSpec)
    is PreOrPostfixNameSpec -> encodePreOrPostfixNameSpec(nameSpec)
}

private fun JsonObjectStringBuilder.encodeStringNameSpec(stringNameSpec: StringNameSpec) =
    addString("value", stringNameSpec.value)

private fun JsonObjectStringBuilder.encodeParameterNameSpec(parameterNameSpec: ParameterNameSpec) {
    addString("parameter", parameterNameSpec.key)
    addStringIfNotNull("defaultValue", parameterNameSpec.defaultValue)
}

private fun JsonObjectStringBuilder.encodeCompoundNameSpec(compoundNameSpec: CompoundNameSpec) {
    addArray("parts") {
        for (part in compoundNameSpec.parts) {
            addObject { encodeNameSpec(part) }
        }
    }
}

private fun JsonObjectStringBuilder.encodePreOrPostfixNameSpec(preOrPostfixNameSpec: PreOrPostfixNameSpec) {
    addObject("main") { encodeNameSpec(preOrPostfixNameSpec.main) }
    addObject(if (preOrPostfixNameSpec.isPrefix) "prefix" else "postfix") { encodeNameSpec(preOrPostfixNameSpec.preOrPostfix) }
}

fun String.decodeAsJsonToNameSpec() = jsonObject(this).decodeNameSpec()

private fun JsonObject.decodeNameSpec(): NameSpec = when {
    hasKey("value") -> decodeStringNameSpec()
    hasKey("parameter") -> decodeParameterNameSpec()
    hasKey("parts") -> decodeCompoundNameSpec()
    hasKey("prefix") -> decodePreOrPostfixNameSpec(true)
    hasKey("postfix") -> decodePreOrPostfixNameSpec(false)
    else -> throw JsonException.create("value]parameter|parts|prefix|postfix", "required, but none found")
}

private fun JsonObject.decodeStringNameSpec() = StringNameSpec(getString("value"))

private fun JsonObject.decodeParameterNameSpec() =
    ParameterNameSpec(getString("parameter"), getStringOrNull("defaultValue"))

private fun JsonObject.decodeCompoundNameSpec() =
    CompoundNameSpec(get("parts") { asArray().toList { asObject().decodeNameSpec() }.toTypedArray() })

private fun JsonObject.decodePreOrPostfixNameSpec(isPrefix: Boolean) = PreOrPostfixNameSpec(
    get("main") { asObject().decodeNameSpec() },
    get(if (isPrefix) "prefix" else "postfix") { asObject().decodeNameSpec() },
    isPrefix,
)
