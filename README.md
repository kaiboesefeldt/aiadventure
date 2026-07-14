# AI Adventure

A local, Ollama-backed interactive adventure application. The JVM server provides the API; the Kotlin/JS React client runs in the browser.

## Prerequisites

- Java (a JVM capable of producing Java 21 bytecode)
- [Ollama](https://ollama.com/) with a local model

## Start

Start Ollama and ensure a model is available:

```bash
ollama serve
ollama pull example-model
```

In separate terminals, start the API and browser client:

```bash
./gradlew :server:run
./gradlew :web:jsBrowserDevelopmentRun
```

The API listens on port 8080. The browser development server prints its local URL when ready.

## Verify

```bash
./gradlew check
```

The command compiles both modules and runs their test suites.
