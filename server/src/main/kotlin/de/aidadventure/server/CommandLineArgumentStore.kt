package de.aidadventure.server

fun interface CommandLineArgumentStore {
    fun get(name: String): Any?

    fun getBoolean(name: String): Boolean? = get(name) as? Boolean

    fun getInt(name: String): Int? = get(name) as? Int

    fun getDouble(name: String): Double? = get(name) as? Double

    fun getString(name: String): String? = get(name) as? String

    companion object {
        val EMPTY = from { null }

        fun from(provider: (String) -> Any?): CommandLineArgumentStore = CommandLineArgumentStore { name -> provider(name) }
    }
}
