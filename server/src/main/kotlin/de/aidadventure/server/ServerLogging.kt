package de.aidadventure.server

internal object ServerLogging {
    private const val DEFAULT_LOG_LEVEL_PROPERTY = "org.slf4j.simpleLogger.defaultLogLevel"
    private const val AI_ADVENTURE_LOG_LEVEL_PROPERTY = "org.slf4j.simpleLogger.log.de.aidadventure"

    fun configure(logLevel: String) {
        System.setProperty(DEFAULT_LOG_LEVEL_PROPERTY, "warn")
        System.setProperty(AI_ADVENTURE_LOG_LEVEL_PROPERTY, logLevel)
    }
}
