package org.plary.genshin.element

import org.bukkit.Bukkit
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.plary.genshin.Genshin
import org.plary.genshin.combat.ReactionResult
import org.plary.genshin.formula.ReactionInput
import java.util.UUID

data class ElementState(val element: Element, val amount: Int, val expiresAt: Long, val sourceId: UUID?)
data class ModifierState(val lunar: Double, val stellar: Double, val expiresAt: Long)

class ElementsManager(private val plugin: Genshin) {
    private val states = mutableMapOf<UUID, ElementState>()
    private val modifiers = mutableMapOf<UUID, ModifierState>()
    private val cooldowns = Cooldowns()

    fun state(id: UUID): ElementState? = states[id]?.takeIf { it.expiresAt > plugin.ticks }
    fun modifier(id: UUID): ModifierState? = modifiers[id]?.takeIf { it.expiresAt > plugin.ticks }

    fun apply(
        target: LivingEntity,
        requested: Element,
        baseAmount: Int,
        sourceId: UUID? = null,
        duration: Long? = null,
        ambient: Boolean = false,
        adjustBonus: Boolean = true,
        lunar: Double = 0.0,
        stellar: Double = 0.0,
        react: Boolean = true,
    ): Boolean {
        if (baseAmount <= 0 || (duration != null && duration <= 0)) return false
        val source = sourceId?.let(Bukkit::getEntity) as? LivingEntity
        val randomWeapon = requested == Element.ELEMENTAL
        if (randomWeapon && sourceId != null && !cooldowns.ready(sourceId, "random-weapon", plugin.ticks)) return false
        val element = if (randomWeapon) Element.base.random() else requested
        if (plugin.blooms.isCore(target.uniqueId)) {
            val amount = if (adjustBonus && source is Player) plugin.equipment.total(source, element, baseAmount, randomWeapon) else baseAmount
            val activated = plugin.blooms.activate(target.uniqueId, element, amount, sourceId)
            if (activated && randomWeapon && sourceId != null) markRandomWeapon(sourceId)
            return activated
        }
        if (!plugin.protection.canAffect(target, sourceId)) return false
        if (target.type == EntityType.SNOW_GOLEM && element in setOf(Element.HYDRO, Element.CRYO)) return false
        if (target.type == EntityType.DROWNED && element == Element.HYDRO) return false
        val now = plugin.ticks
        val id = target.uniqueId
        if (!cooldowns.ready(id, "apply-${element.key}", now)) return false
        val previous = state(id)
        if (!plugin.config.getBoolean("aplying-other-elements-single-player", true) && source is Player && previous?.sourceId == sourceId && previous.element != element) return false
        if (target is Player && plugin.equipment.blocks(target, element, ambient)) return false
        val amount = if (adjustBonus && source is Player) plugin.equipment.total(source, element, baseAmount, randomWeapon) else baseAmount
        if (amount <= 0) return false
        val oldModifier = modifier(id)
        val incomingModifier = if (lunar > 0 || stellar > 0) ModifierState(lunar, stellar,
            now + plugin.config.getLong(if (lunar >= stellar) "states.lunar" else "states.stellar", 100).coerceIn(1, 1728000)) else oldModifier
        val effectiveSource = sourceId ?: previous?.sourceId
        val derivative = if (react) plugin.reactions.quickenReaction(target, element, amount, effectiveSource, incomingModifier) else ReactionResult.NONE
        if (derivative == ReactionResult.BLOCKED) return false
        if (derivative == ReactionResult.TRIGGERED) {
            clearState(id)
            modifiers.remove(id)
        } else if (previous != null && previous.element != element) {
            val reaction = Reaction.determineSpecial(previous.element, element, incomingModifier?.lunar ?: 0.0, incomingModifier?.stellar ?: 0.0)
                ?: Reaction.determine(previous.element, element)
            if (react && reaction != null) {
                val input = ReactionInput(previous.element, previous.amount, element, amount,
                    if (reaction.name.startsWith("LUNAR_")) incomingModifier?.lunar ?: 0.0 else 0.0,
                    if (reaction.name.startsWith("STELLAR_")) incomingModifier?.stellar ?: 0.0 else 0.0)
                if (!plugin.reactions.trigger(target, reaction, input, effectiveSource)) return false
                clearState(id)
                modifiers.remove(id)
            } else {
                // Incompatible elements cancel each other, as in the original plugin.
                clearState(id)
                if (incomingModifier != null) modifiers[id] = incomingModifier
            }
        } else {
            val ticks = (duration ?: plugin.config.getLong("states.${element.key}", 100)).coerceIn(1, 1728000)
            states[id] = ElementState(element, amount, now + ticks, sourceId)
            if (incomingModifier != null) modifiers[id] = incomingModifier
            plugin.holograms.state(target)
            plugin.particles.burst(element, target.location.add(0.0, target.height * 0.5, 0.0), 8)
        }
        cooldowns.mark(id, "apply-${element.key}", now, plugin.config.getLong("elements.apply-cooldown-ticks", 40).coerceIn(0, 1728000))
        if (randomWeapon && sourceId != null) markRandomWeapon(sourceId)
        plugin.holograms.application(target, element, amount)
        if (!ambient && plugin.config.getBoolean("weapons-element-logs", false)) plugin.logger.info("${source?.name ?: "Environment/console"} applied ${element.key} x$amount to ${target.name}.")
        return true
    }

    private fun markRandomWeapon(sourceId: UUID) = cooldowns.mark(sourceId, "random-weapon", plugin.ticks,
        plugin.config.getLong("items.elemental.cooldown-seconds", 2).coerceIn(0, 86400) * 20)

    private fun clearState(id: UUID) { states.remove(id); plugin.holograms.clearState(id) }

    fun tick() {
        states.keys.toList().forEach { id ->
            val entity = Bukkit.getEntity(id) as? LivingEntity
            if (state(id) == null || entity == null || !plugin.protection.eligible(entity)) clearState(id)
            else {
                if (plugin.ticks % 10L == 0L) plugin.holograms.state(entity)
                if (plugin.ticks % 30L == 0L) plugin.particles.burst(states.getValue(id).element, entity.location.add(0.0, 1.0, 0.0), 3)
            }
        }
        modifiers.values.removeIf { it.expiresAt <= plugin.ticks }
        if (plugin.ticks % 20L == 0L) cooldowns.cleanup(plugin.ticks)
    }

    fun clear(id: UUID) { clearState(id); modifiers.remove(id); cooldowns.clear(id) }
    fun clear() { states.keys.toList().forEach(::clearState); modifiers.clear(); cooldowns.clear() }
}
