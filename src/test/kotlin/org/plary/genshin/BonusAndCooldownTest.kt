package org.plary.genshin

import org.plary.genshin.element.Cooldowns
import org.plary.genshin.equipment.Bonus
import org.plary.genshin.util.chance
import org.plary.genshin.util.durationTicks
import java.util.UUID
import kotlin.test.*

class BonusAndCooldownTest {
    @Test fun `flat and percent bonuses apply exactly once and random weapons receive half`() {
        val global = Bonus(flat = 6.0, percent = 20.0)
        val pyro = Bonus(flat = 2.0, percent = 10.0)
        assertEquals(21, (global + pyro).apply(10))
        assertEquals(14, global.apply(10, 0.5))
        assertEquals(Int.MAX_VALUE, Bonus(flat = Int.MAX_VALUE.toDouble()).apply(Int.MAX_VALUE))
    }

    @Test fun `cooldown checks do not refresh expiry and become ready at the boundary`() {
        val cooldowns = Cooldowns()
        val id = UUID.randomUUID()
        assertTrue(cooldowns.ready(id, "reaction", 0))
        cooldowns.mark(id, "reaction", 0, 35)
        assertFalse(cooldowns.ready(id, "reaction", 20))
        assertFalse(cooldowns.ready(id, "reaction", 34))
        assertTrue(cooldowns.ready(id, "reaction", 35))
        assertTrue(cooldowns.ready(id, "apply-hydro", 0))
        assertTrue(cooldowns.ready(UUID.randomUUID(), "reaction", 0))
    }

    @Test fun `lore percentage distinguishes one percent from certainty`() {
        assertEquals(0.01, chance("1%"))
        assertEquals(1.0, chance("1"))
        assertEquals(0.1, chance("10%"))
        assertEquals(0.1, chance("0.1"))
        assertEquals(0.1, chance("10"))
    }

    @Test fun `durations reject invalid and overflowing inputs`() {
        assertEquals(200L, durationTicks("10s"))
        assertEquals(100L, durationTicks("100t"))
        assertEquals(60L, durationTicks("3"))
        listOf("-1s", "0t", "nan", "1.5s", "9223372036854775807s").forEach {
            assertFailsWith<IllegalArgumentException> { durationTicks(it) }
        }
    }
}
