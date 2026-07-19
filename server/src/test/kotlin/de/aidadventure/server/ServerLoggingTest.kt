package de.aidadventure.server

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerLoggingTest {
    private val defaultLevelKey = "org.slf4j.simpleLogger.defaultLogLevel"
    private val aiAdventureLevelKey = "org.slf4j.simpleLogger.log.de.aidadventure"
    private val originalProperties = mapOf(
        defaultLevelKey to System.getProperty(defaultLevelKey),
        aiAdventureLevelKey to System.getProperty(aiAdventureLevelKey),
    )

    @AfterTest
    fun restoreProperties() {
        originalProperties.forEach { (key, value) ->
            if (value == null) System.clearProperty(key) else System.setProperty(key, value)
        }
    }

    @Test
    fun `uses WARN as the global level and INFO for AiAdventure by default`() {
        ServerLogging.configure(ServerRuntimeConfig.from(emptyMap()).logLevel)

        assertEquals("warn", System.getProperty(defaultLevelKey))
        assertEquals("info", System.getProperty(aiAdventureLevelKey))
    }

    @Test
    fun `uses every requested low level only for the AiAdventure namespace`() {
        listOf("INFO" to "info", "DEBUG" to "debug", "TRACE" to "trace").forEach { (argument, expectedLevel) ->
            ServerLogging.configure(ServerRuntimeConfig.from(emptyMap(), arrayOf("--log-level=$argument")).logLevel)

            assertEquals("warn", System.getProperty(defaultLevelKey))
            assertEquals(expectedLevel, System.getProperty(aiAdventureLevelKey))
        }
    }
}
