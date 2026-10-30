package org.plary.genshin.element

import java.util.UUID

/** Expiries use server ticks, rather than wall time, and are committed only after a successful action. */
class Cooldowns {
    private val expiries = mutableMapOf<Pair<UUID, String>, Long>()
    fun ready(id: UUID, key: String, now: Long): Boolean = (expiries[id to key] ?: Long.MIN_VALUE) <= now
    fun mark(id: UUID, key: String, now: Long, duration: Long) {
        expiries[id to key] = Math.addExact(now, duration.coerceAtLeast(0))
    }
    fun cleanup(now: Long) { expiries.values.removeIf { it <= now } }
    fun clear(id: UUID) { expiries.keys.removeIf { it.first == id } }
    fun clear() { expiries.clear() }
}
