package org.plary.genshin.formula

import org.plary.genshin.element.Element
import org.plary.genshin.element.Reaction

data class ReactionInput(
    val a: Element,
    val amountA: Int,
    val b: Element,
    val amountB: Int,
    val lunar: Double = 0.0,
    val stellar: Double = 0.0,
    val extra: Map<String, Double> = emptyMap(),
) {
    fun amount(element: Element): Int = (if (a == element) amountA else 0) + (if (b == element) amountB else 0)
}

object DamageFormula {
    fun variables(input: ReactionInput, multiplier: Double, pulse: Double): Map<String, Double> = buildMap {
        Element.base.forEach { element ->
            val value = input.amount(element).toDouble()
            put(element.key, value)
            put("amount_${element.key}", value)
        }
        put("a", input.amountA.toDouble()); put("b", input.amountB.toDouble())
        put("amount_a", input.amountA.toDouble()); put("amount_b", input.amountB.toDouble())
        put("sum", input.amountA.toDouble() + input.amountB)
        put("multiplier", multiplier); put("pulse_multiplier", pulse)
        putAll(input.extra)
    }

    fun defaultDamage(reaction: Reaction, input: ReactionInput, multiplier: Double, pulse: Double): Double {
        val sum = input.amountA.toDouble() + input.amountB
        return when (reaction) {
            Reaction.AGGRAVATE -> sum + (input.extra["new_electro"] ?: 0.0) * multiplier
            Reaction.SPREAD -> (input.extra["new_dendro"] ?: 0.0) * multiplier
            Reaction.HYPERBLOOM -> (sum + (input.extra["new_electro"] ?: 0.0)) * multiplier
            Reaction.CRYSTALLIZE -> sum * pulse * multiplier
            else -> sum * multiplier
        }.coerceAtLeast(0.0)
    }

    fun modifier(input: ReactionInput, lunarMultiplier: Double, stellarMultiplier: Double): Double = when {
        input.lunar > 0 && input.lunar >= input.stellar -> lunarMultiplier * (1.0 + input.lunar / 100.0)
        input.stellar > 0 -> stellarMultiplier * (1.0 + input.stellar / 100.0)
        else -> 1.0
    }
}
