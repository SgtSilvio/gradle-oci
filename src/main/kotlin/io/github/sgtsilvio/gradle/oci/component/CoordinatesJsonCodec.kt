package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.internal.json.JsonObject
import io.github.sgtsilvio.gradle.oci.internal.json.JsonObjectStringBuilder
import io.github.sgtsilvio.gradle.oci.internal.json.getString

fun JsonObjectStringBuilder.encodeCoordinates(coordinates: Coordinates) {
    addString("group", coordinates.group)
    addString("name", coordinates.name)
}

fun JsonObjectStringBuilder.encodeVersionedCoordinates(versionedCoordinates: VersionedCoordinates) {
    encodeCoordinates(versionedCoordinates.coordinates)
    addString("version", versionedCoordinates.version)
}

fun JsonObject.decodeCoordinates() = Coordinates(getString("group"), getString("name"))

fun JsonObject.decodeVersionedCoordinates() = VersionedCoordinates(decodeCoordinates(), getString("version"))
