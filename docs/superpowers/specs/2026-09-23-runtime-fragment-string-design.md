# Runtime Fragment String Shorthand

`runtime-fragments` entries may use either the existing mapping form with a `fragment` field or a scalar string value. A scalar string is interpreted directly as the fragment text. Both forms produce the same `RuntimeFragmentDefinition`; blank text and unsupported value shapes remain invalid.

This change is limited to scenario frontmatter parsing and its tests. Runtime selection and server APIs do not change.
