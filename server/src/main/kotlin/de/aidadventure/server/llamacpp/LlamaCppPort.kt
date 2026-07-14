package de.aidadventure.server.llamacpp

object LlamaCppPort {
    const val DEFAULT = 8081

    fun parse(value: String): Int = value.toIntOrNull()
        ?.takeIf { it in 1..65535 }
        ?: throw IllegalArgumentException("llama.cpp port must be between 1 and 65535.")
}
