package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.internal.json.JsonObject
import io.github.sgtsilvio.gradle.oci.internal.json.JsonObjectStringBuilder
import io.github.sgtsilvio.gradle.oci.internal.json.getString

internal fun JsonObjectStringBuilder.encodeCoordinates(coordinates: Coordinates) {
    addString("group", coordinates.group)
    addString("name", coordinates.name)
}

internal fun JsonObjectStringBuilder.encodeVersionedCoordinates(versionedCoordinates: VersionedCoordinates) {
    encodeCoordinates(versionedCoordinates.coordinates)
    addString("version", versionedCoordinates.version)
}

internal fun JsonObject.decodeCoordinates() = Coordinates(getString("group"), getString("name"))

internal fun JsonObject.decodeVersionedCoordinates() = VersionedCoordinates(decodeCoordinates(), getString("version"))
