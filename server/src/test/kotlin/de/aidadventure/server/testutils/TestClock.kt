package de.aidadventure.server.testutils

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class TestClock(
    val baseClock: Clock,
) : Clock() {
    private var advancement: Duration = 0.seconds

    constructor(instant: Instant, zoneId: ZoneId = ZoneId.of("UTC")) :
        this(fixed(instant, zoneId))

    override fun getZone(): ZoneId = baseClock.zone

    override fun withZone(zone: ZoneId): Clock = TestClock(baseClock.withZone(zone)).apply { advance(advancement) }

    override fun instant(): Instant = baseClock.instant() + advancement.toJavaDuration()

    fun advance(increment: Duration) {
        advancement += increment
    }

    @Suppress("unused")
    fun reset() {
        advancement = 0.seconds
    }
}
