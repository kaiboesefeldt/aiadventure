# Disable Ollama thinking design

## Goal

Every game-master request disables Ollama's reasoning mode so the model returns
only its game-master response.

## Change

`LangChain4jOllamaGateway` extends its existing default
`OllamaChatRequestParameters` with `think(false)`. LangChain4j serializes this
as Ollama's `think: false` request option for every call made by the gateway.

## Boundaries

The change is server-internal. The browser API, persisted sessions, prompt
construction order, error mapping, and operational logging remain unchanged.

## Verification

A focused server test will assert that the gateway's default Ollama request
parameters have thinking disabled. The complete Gradle check will then confirm
that both modules still compile and pass their tests.
