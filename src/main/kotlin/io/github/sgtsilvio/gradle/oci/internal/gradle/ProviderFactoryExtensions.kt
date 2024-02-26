package io.github.sgtsilvio.gradle.oci.internal.gradle

import org.gradle.api.credentials.PasswordCredentials
import org.gradle.api.provider.ProviderFactory
import org.gradle.kotlin.dsl.credentials

internal fun ProviderFactory.passwordCredentials(identity: String) = credentials(PasswordCredentials::class, identity)

internal fun ProviderFactory.optionalPasswordCredentials(identity: String) = provider {
    if (gradleProperty(identity + "Username").isPresent) {
        passwordCredentials(identity).get()
    } else null
}
