package de.aidadventure.server.prefill

import de.aidadventure.server.llamacpp.LlamaCppSlot

/** Serializes slot ownership across all live coordinators for one server process. */
internal class PrefillSlotLeaseManager(
    private val gateway: PrefillGateway,
) {
    private val lock = Any()
    private val leased = mutableMapOf<Int, MutableSet<LlamaCppSlot>>()

    fun acquire(port: Int): LlamaCppSlot = synchronized(lock) {
        val portLeases = leased.getOrPut(port, ::mutableSetOf)
        val slot = gateway.acquireIdleSlot(port, portLeases.toSet())
        check(portLeases.add(slot)) { "llama.cpp slot ${slot.id} is already leased." }
        slot
    }

    fun release(port: Int, slot: LlamaCppSlot) = synchronized(lock) {
        val slots = leased[port] ?: return@synchronized
        if (slot !in slots) return@synchronized
        gateway.releaseSlot(port, slot)
        slots.remove(slot)
        if (slots.isEmpty()) leased.remove(port)
    }
}
