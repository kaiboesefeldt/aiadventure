# Structured Output with llama.cpp

## Rule: Avoid a small `n_predict` limit with a finite JSON grammar

For short, schema-bound llama.cpp completion tasks, do not send a separate
small `n_predict` limit. llama.cpp translates the JSON schema into a grammar,
which already constrains the output through features such as `enum`, `const`,
`minLength`, and `maxLength`.

An additional token limit can truncate the output before the closing JSON
brace. The server then sees invalid JSON and discards the result, even though
the grammar would have constrained the valid answer. This is especially likely
with small secondary models when they need to generate many tokens before
reaching a valid grammar alternative.

If an output bound is needed, put it in the schema. For example, to allow an
optional text value to be empty:

```json
{
  "anyOf": [
    { "const": "" },
    { "type": "string", "minLength": 3, "maxLength": 96 }
  ]
}
```

Before introducing a schema construction, run a direct smoke test against the
local llama-server to confirm that it translates the JSON Schema features into
the intended grammar. Server-side semantic validation remains necessary even
when a schema is used.
