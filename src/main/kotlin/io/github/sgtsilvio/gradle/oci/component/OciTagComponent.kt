package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.mapping.OciImageReference

internal data class OciTagComponent(val imageReference: OciImageReference, val parentCapability: Coordinates)

internal fun OciComponent.asTagOrNull(): OciTagComponent? {
    if (!capabilities.isEmpty()) {
        return null
    }
    val bundle = bundleOrPlatformBundles as? OciComponent.Bundle
        ?: throw IllegalStateException("tag component must only have 1 bundle ($this)")
    val parentCapabilities = bundle.parentCapabilities
    check(parentCapabilities.size == 1) { "tag component must have exactly 1 parent capability" }
    check(bundle.creationTime == null) { "tag component must not set creationTime" }
    check(bundle.author == null) { "tag component must not set author" }
    check(bundle.user == null) { "tag component must not set user" }
    check(bundle.ports.isEmpty()) { "tag component must not set ports" }
    check(bundle.environment.isEmpty()) { "tag component must not set environment" }
    check(bundle.command == null) { "tag component must not set command" }
    check(bundle.volumes.isEmpty()) { "tag component must not set volumes" }
    check(bundle.workingDirectory == null) { "tag component must not set workingDirectory" }
    check(bundle.stopSignal == null) { "tag component must not set stopSignal" }
    check(bundle.configAnnotations.isEmpty()) { "tag component must not set configAnnotations" }
    check(bundle.configDescriptorAnnotations.isEmpty()) { "tag component must not set configDescriptorAnnotations" }
    check(bundle.manifestAnnotations.isEmpty()) { "tag component must not set manifestAnnotations" }
    check(bundle.manifestDescriptorAnnotations.isEmpty()) { "tag component must not set manifestDescriptorAnnotations" }
    check(bundle.layers.isEmpty()) { "tag component must not set layers" }
    check(indexAnnotations.isEmpty()) { "tag component must not set indexAnnotations" }
    return OciTagComponent(imageReference, parentCapabilities[0])
}
