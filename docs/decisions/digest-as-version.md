# Using Digest as Version

When declaring a dependency on an OCI image that is pulled from an OCI registry it should be possible to specify a tag or a digest.
It is a no-brainer to use the tag as the version in the dependency coordinates (we call a version that represents a tag "tag version" in this document).
When using a digest instead of a tag, it is logical to specify the digest as the version as well (we call a version that represents a digest "digest version" in this document).

When using a digest as version, multiple constraints need to be taken into account:

1. Allowed characters in file system paths:

   The version is part of file and directory names and so must avoid some characters.

   The following characters should be avoided: `NUL, \, /, :, *, ?, ", <, >, |`

2. Allowed characters in OCI digests:

   Unfortunately digests can contain the character `:` that is problematic in some file systems.
   A replacements character need to be found to "escape" this problematic character.

   Digest regex:
   ```
   digest                ::= algorithm ":" encoded
   algorithm             ::= algorithm-component (algorithm-separator algorithm-component)*
   algorithm-component   ::= [a-z0-9]+
   algorithm-separator   ::= [+._-]
   encoded               ::= [a-zA-Z0-9=_-]+
   ```

3. Allowed characters in OCI tags:

   It must be possible to determine if a version represents a tag or a digest.
   When replacing characters in a digest version, it must still be distinguishable from a tag version.

   Tag regex: `[a-zA-Z0-9_][a-zA-Z0-9._-]{0,127}`

4. Allowed characters in Gradle's dependency short notation:

   It must be convenient to specify an OCI image dependency the same as any other dependency.

   Dependency short notation scheme: `<group>:<name>:<version>[!!]:<classifier>@<extension>`

5. Allowed characters in URLs:

   The version is part of URLs and so must avoid some characters.

   Although more characters can be used (unescaped) in URLs, best is to restrict to: `A-Za-z0-9`, `$-_.+!*'()`

6. The digest version should be easy to type.

   Therefore it makes sense to limit the characters set for replacement characters to ASCII symbols.

## Use Digest As-Is

When using a digest without any modifications as a version, it does not satisfy constraints 1 and 4 because of the `:` character in the digest.

The Gradle dependency short notation
`library:eclipse-temurin:sha256:56be7e97bc4d2fffafdda7e8198741fd96513bc12f05d6da9f18c2577a1d5649` would be interpreted as version = `sha256` and classifier = `56be...5649`.

It could be an option to not support the short dependency notation for digest versions, because it would still be possible to specify the version with the rich version syntax:
```
add("library:eclipse-temurin") {
    version {
        require("sha256:f9d5e219ba439970ed373ff6e9f5ba2746db45789949f9f3fa474aa6bab1eae7")
    }
}
```

As this still violates constraint 1 (the `:` character is problematic in file system paths), using the digest as-is is not considered an option.

## Replace the `:` Character with Another Character

Considered replacement options for `:`:

- `\`, `/`, `:`, `*`, `?`, `"`, `<`, `>`, `|`

  CONS:
  - should be avoided in file system paths, violates constraint 1

- `.`, `-`, `_`

  CONS:
  - may be used inside a tag, violates constraint 3
  - may be used inside a digest, it would not be possible to split the algorithm from the encoded part, violates constraint 2

- `+`, `=`

  CONS:
  - may be used inside a digest, it would not be possible to split the algorithm from the encoded part, violates constraint 2

- `..`

  CONS:
  - can not be used inside digest, but may be used inside tag, violates constraint 3

- `==`

  CONS:
  - can not be used inside tag, but may be used inside digest, it would not be possible to split the algorithm from the encoded part, violates constraint 2

- `--`, `__`

  CONS:
  - may be used inside tag, violates constraint 3
  - may be used inside digest, it would not be possible to split the algorithm from the encoded part, violates constraint 2

- `++`

  PROS:
  - can not be used inside digest or tag

  CONS:
  - 2 characters

- `@`

  CONS
  - does not work in dependency short notation, encoded part would be interpreted as extension, violates constraint 4

- `#`, `%`

  CONS:
  - does not work unescaped in urls, violates constraint 5

- `$`
  
  CONS:
  - may be used for string substitution, violates constraint 4
  - not good as separator characters, as they fill too much space and don't visually separate

- `(`, `[`, `{` (and closing alternatives)

  CONS:
  - not good as separator characters, expected in pairs

- `,`, `;`

  CONS:
  - confusing as separator characters inside a token, expected to separate multiple tokens (for example a list of digests)

- `§`, `&`

  CONS:
  - not good as separator characters, as they fill too much space and don't visually separate

- `^`

  CONS:
  - feels semantically wring as a separator character, looks mathematically

- `` ` ``

  CONS:
  - hard to type, violates constraint 6

- `'`, `~`

  PROS:
  - do not violate any constraint
  CONS:
  - not in the list of characters best to use in URLs

- `!`

  PROS:
  - does not violate any constraint
  - does not conflict with forced versions in dependency short notation (`!!`)
  - makes semantically some sense because when using a digest, one wants to _explicitly_ pin to the digest

## Decision

`!` replaces the `:` character of a digest in a digest version.

Example dependency short notation: `library:eclipse-temurin:sha256!56be7e97bc4d2fffafdda7e8198741fd96513bc12f05d6da9f18c2577a1d5649`
