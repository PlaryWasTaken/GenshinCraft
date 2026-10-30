package org.plary.genshin

import org.plary.genshin.element.Element
import org.plary.genshin.element.Reaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReactionTest {
    @Test fun `special reactions require the matching trigger and pair`() {
        val pairs = listOf(
            Triple(Element.HYDRO, Element.GEO, Reaction.LUNAR_CRYSTALLIZE),
            Triple(Element.HYDRO, Element.DENDRO, Reaction.LUNAR_BLOOM),
            Triple(Element.HYDRO, Element.ELECTRO, Reaction.LUNAR_CHARGED),
            Triple(Element.CRYO, Element.ELECTRO, Reaction.STELLAR_CONDUCT),
            Triple(Element.CRYO, Element.ANEMO, Reaction.STELLAR_SWIRL))
        pairs.forEach { (a, b, reaction) ->
            val lunar = if (reaction.name.startsWith("LUNAR")) 1.0 else 0.0
            val stellar = if (lunar == 0.0) 1.0 else 0.0
            assertEquals(reaction, Reaction.determineSpecial(a, b, lunar, stellar))
            assertEquals(reaction, Reaction.determineSpecial(b, a, lunar, stellar))
            assertNull(Reaction.determineSpecial(a, b, 0.0, 0.0))
            assertNull(Reaction.determineSpecial(a, b, stellar, lunar))
        }
    }
    @Test fun `reaction pairs are symmetric and match the original table`() {
        val pairs = listOf(
            Triple(Element.HYDRO, Element.CRYO, Reaction.FREEZE),
            Triple(Element.PYRO, Element.CRYO, Reaction.MELT),
            Triple(Element.ELECTRO, Element.HYDRO, Reaction.ELECTRO_CHARGED),
            Triple(Element.PYRO, Element.DENDRO, Reaction.BURNING),
            Triple(Element.ELECTRO, Element.PYRO, Reaction.OVERLOAD),
            Triple(Element.ELECTRO, Element.CRYO, Reaction.SUPERCONDUCT),
            Triple(Element.PYRO, Element.HYDRO, Reaction.VAPORIZE),
            Triple(Element.DENDRO, Element.GEO, Reaction.ROOTS),
            Triple(Element.DENDRO, Element.ELECTRO, Reaction.QUICKEN),
            Triple(Element.DENDRO, Element.HYDRO, Reaction.BLOOM),
        ) + listOf(Element.PYRO, Element.HYDRO, Element.CRYO, Element.ELECTRO).flatMap {
            listOf(Triple(Element.ANEMO, it, Reaction.SWIRL), Triple(Element.GEO, it, Reaction.CRYSTALLIZE))
        }
        pairs.forEach { (a, b, result) ->
            assertEquals(result, Reaction.determine(a, b), "$a + $b")
            assertEquals(result, Reaction.determine(b, a), "$b + $a")
        }
    }

    @Test fun `same elements and incompatible pairs never react`() {
        Element.entries.forEach { assertNull(Reaction.determine(it, it)) }
        assertNull(Reaction.determine(Element.ANEMO, Element.GEO))
        assertNull(Reaction.determine(Element.ANEMO, Element.DENDRO))
        Element.base.forEach { assertNull(Reaction.determine(Element.ELEMENTAL, it)) }
    }
}
