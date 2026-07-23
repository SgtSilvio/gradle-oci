# Registry in Coordinates

An OCI image is addressed by a registry and an image reference, while a Gradle dependency is addressed by coordinates
and resolved from the repositories of the consuming build.
This plugin maps the registry to a Gradle repository, which is a good fit as long as a build declares the images it
consumes itself.

It stops fitting as soon as an image is consumed that was built by another build.
Parent images are transitive dependencies, and repositories are not part of a published component, so the consuming
build resolves the parent images of an image against its own repositories.
The registry of a parent image therefore has to be declared in every consuming build, although only the build that
defines the image knows the registry.
The consuming build has no way of learning it, because the coordinates it receives contain group, name and version, but
not the registry.

Carrying the registry in the coordinates removes that gap: a coordinate is then self contained, one repository can serve
all registries, and a consuming build declares nothing.

The registry is part of the group and not of the name, because the group already represents the namespace of the image
and a repository can filter on it.

## Constraints

The constraints of [using the digest as version](digest-as-version.md) apply to the group as well:

1. Allowed characters in file system paths:

   The group is part of file and directory names and so must avoid some characters.

   The following characters should be avoided: `NUL, \, /, :, *, ?, ", <, >, |`

2. Allowed characters in registry hosts:

   The separator must be distinguishable from the host.

   Host regex: `[a-zA-Z0-9][a-zA-Z0-9.-]*` optionally followed by `":" port`

3. Allowed characters in image namespaces:

   The separator must be distinguishable from the namespace.

   Path component regex: `[a-z0-9]+([._-][a-z0-9]+)*`, path components are separated by `/`, which this plugin maps to
   `.` in the group

4. Allowed characters in Gradle's dependency short notation:

   It must be convenient to specify a registry qualified image dependency the same as any other dependency.

   Dependency short notation scheme: `<group>:<name>:<version>[!!]:<classifier>@<extension>`

5. Allowed characters in URLs:

   The group is part of URLs and so must avoid some characters.

   Although more characters can be used (unescaped) in URLs, best is to restrict to: `A-Za-z0-9`, `$-_.+!*'()`

6. The separator should be easy to type.

## Considered Separators

- `\`, `/`, `:`, `*`, `?`, `"`, `<`, `>`, `|`

  CONS:
  - should be avoided in file system paths, violates constraint 1

- `.`, `-`, `_`

  CONS:
  - may be used inside a host, violates constraint 2
  - may be used inside a namespace, violates constraint 3

- `..`

  CONS:
  - can not be used inside a host or namespace, but reads as a typo

- `@`

  CONS:
  - does not work in dependency short notation, the rest would be interpreted as extension, violates constraint 4

- `#`, `%`

  CONS:
  - does not work unescaped in urls, violates constraint 5

- `$`

  CONS:
  - may be used for string substitution, violates constraint 4

- `(`, `[`, `{` (and closing alternatives)

  CONS:
  - not good as separator characters, expected in pairs

- `,`, `;`

  CONS:
  - confusing as separator characters inside a token, expected to separate multiple tokens

- `^`, `§`, `&`, `` ` ``

  CONS:
  - hard to type or semantically misleading, violates constraint 6

- `'`, `~`

  PROS:
  - do not violate any constraint

  CONS:
  - not in the list of characters best to use in URLs

- `+`

  PROS:
  - does not violate any constraint

  CONS:
  - reads as a concatenation of two parts rather than a separation

- `!`

  PROS:
  - does not violate any constraint
  - already used to represent the boundary that OCI spells differently, the `:` of a digest
  - does not conflict with forced versions in dependency short notation (`!!`), which apply to the version

## Decision

`!` separates the registry host from the namespace in the group of a registry qualified coordinate.

Example dependency short notation:
`public.ecr.aws!hivemq.base-images:eclipse-temurin:sha256!5e349bf28c22a00d7ca2dea836586725698ee7252a178ea94c40ba45a59ec4a0`

A group without `!` keeps its current meaning, an image of Docker Hub whose first group segment is dropped as a top
level domain.

## Consequences

- A registry qualified coordinate is served by a single repository that derives the registry from the group, so no
  registry has to be declared for it.
- The namespace of a registry qualified group is not truncated, so no image mapping is needed for a namespace with more
  than one path component.
- A registry that requires credentials still has to be declared. A registry qualified coordinate uses the credentials of
  a declared registry with the same host, and is pulled anonymously otherwise.
- A registry host with an explicit port can not be expressed, because `:` can not be used in a group. Such a registry
  has to be declared.
