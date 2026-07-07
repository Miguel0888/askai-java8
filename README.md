# AskAI Java 8

Java 8 compatible AskAI multi-project.

## Modules

- `ollama4j-java8`: small Java 8 Ollama client backed by `HttpURLConnection`.
- `askai-app`: Swing application that uses the embedded Java 8 Ollama client.

## Features

- Connect to a remote Ollama server.
- Refresh installed remote Ollama models.
- Chat with streamed responses.
- Pull Ollama registry models on the remote Ollama server.
- Search HuggingFace GGUF models.
- List and download GGUF files from HuggingFace.
- Use HTTP proxy settings for HuggingFace access.

## Build

```bash
bash chatgpt-build.sh
```

or with Gradle 7.6.4:

```bash
gradle --no-daemon clean :askai-app:fatJar
```

The runnable jar is written to:

```text
askai-app/build/libs/askai-java8-0.1.0.jar
```

## Runtime

Requires Java 8 or newer.

The application stores configuration in:

```text
%APPDATA%\.askai-java8\askai-java8.properties
```
