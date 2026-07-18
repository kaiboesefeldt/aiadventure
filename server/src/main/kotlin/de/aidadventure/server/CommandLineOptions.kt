package de.aidadventure.server

fun interface CommandLineOptions {
    fun toArgumentValues(): Map<String, Any?>
}
