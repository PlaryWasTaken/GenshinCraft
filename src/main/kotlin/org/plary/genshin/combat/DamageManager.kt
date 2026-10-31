package org.plary.genshin.combat

import org.bukkit.Bukkit
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.plary.genshin.Genshin
import org.plary.genshin.element.Reaction
import org.plary.genshin.element.Element
import org.plary.genshin.formula.DamageFormula
import org.plary.genshin.formula.ExpressionEvaluator
import org.plary.genshin.formula.ReactionInput
import org.plary.genshin.util.numeric
import java.util.UUID

class DamageManager(private val plugin: Genshin) {
    private val invalidFormulas = mutableSetOf<String>()
    private val hits = mutableMapOf<Pair<UUID, Reaction>, Long>()
    var dealingReactionDamage = false
        private set

    fun multiplier(reaction: Reaction, player: Boolean = false): Double {
        val path = "reactions.multipliers.${reaction.key}"
        val base = plugin.config.numeric(path, 1.0)
        return (if (player) plugin.config.numeric("$path-players", base) else base).coerceAtLeast(0.0)
    }

    fun calculate(reaction: Reaction, input: ReactionInput, player: Boolean): Double {
        val multiplier = multiplier(reaction, player)
        val pulse = plugin.config.numeric("parameters.crystallize.pulse-multiplier", 0.5).coerceAtLeast(0.0)
        var result = DamageFormula.defaultDamage(reaction, input, multiplier, pulse)
        val formula = plugin.config.getString("formulas.${reaction.key}")
        if (plugin.config.getBoolean("formulas.ModifyFormulas", false) && !formula.isNullOrBlank()) {
            try { result = ExpressionEvaluator.evaluate(formula, DamageFormula.variables(input, multiplier, pulse)) }
            catch (ex: IllegalArgumentException) {
                if (invalidFormulas.add("${reaction.key}:$formula")) plugin.logger.warning("Invalid ${reaction.key} formula: ${ex.message}. Using default damage.")
            }
        }
        result *= DamageFormula.masteryBonus(input.extra["elemental_mastery"] ?: 0.0)
        result *= DamageFormula.modifier(input, plugin.config.numeric("states.lunar-multiplier", 1.0), plugin.config.numeric("states.stellar-multiplier", 1.0))
        result *= input.extra["special_scale"] ?: 1.0
        if (reaction in setOf(Reaction.LUNAR_CRYSTALLIZE, Reaction.LUNAR_CHARGED, Reaction.STELLAR_CONDUCT))
            result *= plugin.config.numeric("parameters.${reaction.key}.damage-scale", 0.25).coerceAtLeast(0.0)
        return result.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0
    }

    fun hit(target: LivingEntity, reaction: Reaction, input: ReactionInput, sourceId: UUID?, ignoreCooldown: Boolean = false): Boolean {
        if (!plugin.protection.canAffect(target, sourceId)) return false
        val key = target.uniqueId to reaction
        val icd = plugin.config.getLong("reactions.hit-icd-ticks", 4).coerceAtLeast(0)
        if (!ignoreCooldown && (hits[key] ?: Long.MIN_VALUE) > plugin.ticks) return false
        val type = when (reaction) {
            Reaction.LUNAR_CHARGED, Reaction.STELLAR_CONDUCT -> Element.ELECTRO
            Reaction.STELLAR_SWIRL -> if ("special_scale" in input.extra) Element.CRYO else Element.ANEMO
            Reaction.LUNAR_CRYSTALLIZE -> Element.GEO
            Reaction.FREEZE, Reaction.SUPERCONDUCT -> Element.CRYO
            Reaction.MELT, Reaction.BURNING, Reaction.OVERLOAD, Reaction.VAPORIZE, Reaction.BURGEON -> Element.PYRO
            Reaction.ELECTRO_CHARGED, Reaction.AGGRAVATE, Reaction.HYPERBLOOM -> Element.ELECTRO
            Reaction.CRYSTALLIZE -> Element.GEO
            Reaction.SWIRL -> Element.ANEMO
            Reaction.QUICKEN, Reaction.SPREAD, Reaction.BLOOM, Reaction.ROOTS, Reaction.LUNAR_BLOOM -> Element.DENDRO
        }
        val damage = calculate(reaction, input, target is Player) * resistanceMultiplier(type)
        if (damage <= 0) return false
        hits[key] = plugin.ticks + icd
        val source = sourceId?.let(Bukkit::getEntity)?.takeIf { it.isValid && it.world == target.world }
        val builder = DamageSource.builder(DamageType.MAGIC)
        if (source != null) builder.withCausingEntity(source).withDirectEntity(source)
        // Paper dispatches ordinary damage events, preserving protection, attribution and cancellation.
        val oldHealth = target.health + target.absorptionAmount
        val oldImmunity = target.noDamageTicks
        val oldDamage = target.lastDamage
        dealingReactionDamage = true
        try {
            target.noDamageTicks = 0
            target.damage(damage, builder.build())
        } finally {
            if (target.isValid && !target.isDead) {
                target.noDamageTicks = oldImmunity
                target.lastDamage = oldDamage
            }
            dealingReactionDamage = false
        }
        val dealt = (oldHealth - target.health - target.absorptionAmount).coerceAtLeast(0.0)
        if (dealt > 0) plugin.holograms.damage(target, reaction, input, dealt)
        return dealt > 0
    }

    fun resistanceMultiplier(type: Element?): Double =
        (1.0 - plugin.config.numeric("damage.resistance.${type?.key ?: "physical"}", 0.0)).coerceAtLeast(0.0)

    fun cleanup() { hits.values.removeIf { it <= plugin.ticks } }
    fun clear(id: UUID) { hits.keys.removeIf { it.first == id } }
    fun clear() { hits.clear(); invalidFormulas.clear() }
}
