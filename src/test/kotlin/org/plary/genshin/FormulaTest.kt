package org.plary.genshin

import org.plary.genshin.element.Element
import org.plary.genshin.element.Reaction
import org.plary.genshin.formula.DamageFormula
import org.plary.genshin.formula.ExpressionEvaluator.evaluate
import org.plary.genshin.formula.ReactionInput
import kotlin.test.*

class FormulaTest {
    private val input = ReactionInput(Element.HYDRO, 8, Element.PYRO, 12)

    @Test fun `precedence parentheses and unary signs`() {
        assertEquals(14.0, evaluate("2+3*4", emptyMap()))
        assertEquals(-10.0, evaluate("-(2+3)*2", emptyMap()))
        assertEquals(6.0, evaluate("2*-(-3)", emptyMap()))
        assertEquals(2.5, evaluate("5/2", emptyMap()))
    }

    @Test fun `subtraction is not swallowed by variable names`() {
        val variables = DamageFormula.variables(input, 1.5, 0.5)
        assertEquals(4.0, evaluate("pyro-hydro", variables))
        assertEquals(22.0, evaluate("PYRO*2-HYDRO/4", variables))
        assertEquals(15.0, evaluate("((hydro + pyro) / 2) * multiplier", variables))
    }

    @Test fun `documented English variables remain accepted`() {
        val vars = DamageFormula.variables(input, 2.0, 0.5) + ("new_electro" to 3.0)
        assertEquals(4.0, evaluate("amount_hydro*pulse_multiplier", vars))
        assertEquals(3.0, evaluate("new_electro", vars))
    }

    @Test fun `malformed or unsafe formulas fail so the caller can use defaults`() {
        listOf("", "1/0", "((1)", "2 2", "1+", "unknown+1", "pow(2)", "2**3").forEach { formula ->
            assertFailsWith<IllegalArgumentException>(formula) { evaluate(formula, emptyMap()) }
        }
        assertFailsWith<IllegalArgumentException> { evaluate("(".repeat(100) + "1" + ")".repeat(100), emptyMap()) }
    }

    @Test fun `default damage uses doubles instead of overflowing integer sums`() {
        val large = ReactionInput(Element.PYRO, Int.MAX_VALUE, Element.HYDRO, Int.MAX_VALUE)
        assertEquals(4.0, DamageFormula.defaultDamage(Reaction.VAPORIZE, large, 2.0, 0.5))
    }

    @Test fun `derivatives retain original element amounts and new application`() {
        val quicken = ReactionInput(Element.DENDRO, 10, Element.ELECTRO, 20, extra = mapOf("new_electro" to 5.0, "new_dendro" to 6.0))
        assertEquals(2.0, DamageFormula.defaultDamage(Reaction.AGGRAVATE, quicken, 2.0, 0.5))
        assertEquals(2.0, DamageFormula.defaultDamage(Reaction.SPREAD, quicken, 2.0, 0.5))
        val bloom = ReactionInput(Element.DENDRO, 10, Element.HYDRO, 20, extra = mapOf("new_electro" to 5.0))
        assertEquals(4.0, DamageFormula.defaultDamage(Reaction.HYPERBLOOM, bloom, 2.0, 0.5))
        assertEquals(2.0, DamageFormula.defaultDamage(Reaction.CRYSTALLIZE, bloom, 2.0, 0.5))
    }

    @Test fun `mastery has bounded diminishing returns`() {
        assertEquals(1.0, DamageFormula.masteryBonus(0.0))
        assertEquals(1.0 + 100.0 / 1150.0, DamageFormula.masteryBonus(100.0), 1e-9)
        assertTrue(DamageFormula.masteryBonus(100000.0) < 2.0)
    }

    @Test fun `reaction mastery uses snapshots and prevents switching abuse`() {
        val first = java.util.UUID.randomUUID()
        val second = java.util.UUID.randomUUID()
        assertEquals(1.0, DamageFormula.reactionMastery(1.0, first, 100.0, first))
        assertEquals(100.0, DamageFormula.reactionMastery(1.0, first, 100.0, second))
        assertEquals(1.0, DamageFormula.reactionMastery(1.0, first, 0.0, null))
    }

    @Test fun `the strongest celestial modifier wins with lunar winning ties`() {
        assertEquals(1.2, DamageFormula.modifier(input.copy(lunar = 20.0), 1.0, 1.0))
        assertEquals(2.4, DamageFormula.modifier(input.copy(lunar = 20.0, stellar = 20.0), 2.0, 3.0))
        assertEquals(3.9, DamageFormula.modifier(input.copy(lunar = 10.0, stellar = 30.0), 2.0, 3.0), 1e-9)
    }
}
