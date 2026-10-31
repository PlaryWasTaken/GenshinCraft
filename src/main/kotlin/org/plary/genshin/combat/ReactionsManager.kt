package org.plary.genshin.combat

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.AttributeModifier
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.BlockDisplay
import org.bukkit.attribute.Attribute
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.plary.genshin.Genshin
import org.plary.genshin.element.*
import org.plary.genshin.formula.ReactionInput
import org.plary.genshin.util.numeric
import java.util.UUID

enum class ReactionResult { NONE, BLOCKED, TRIGGERED }
private data class Quicken(val dendro: Int, val electro: Int, val expiresAt: Long, val mastery: Double, val sourceId: UUID?)
private data class PeriodicHit(val target: UUID, val world: UUID, val reaction: Reaction, val input: ReactionInput, val sourceId: UUID?, var remaining: Int, val interval: Long, var next: Long)
private data class ReactionArea(val center: Location, val reaction: Reaction, val input: ReactionInput, val sourceId: UUID?, val radius: Double, val players: Boolean, val created: Long, val expiresAt: Long, val interval: Long, var next: Long, val spreadElement: Element?, val spreadAmount: Int)

private data class ActiveShield(val previousAbsorption: Double, val expires: Long)
private data class ShieldCrystal(val display: BlockDisplay, val amount: Double, val expires: Long)

class ReactionsManager(private val plugin: Genshin) {
    private val crystals = mutableListOf<ShieldCrystal>()
    private val shields = mutableMapOf<UUID, ActiveShield>()
    private val shieldKey = NamespacedKey(plugin, "crystallize-shield")
    private val cooldowns = Cooldowns()
    private val quicken = mutableMapOf<UUID, Quicken>()
    private val periodic = mutableListOf<PeriodicHit>()
    private val areas = mutableListOf<ReactionArea>()
    private val physicalResistanceDown = mutableMapOf<UUID, Long>()

    fun physicalDamageMultiplier(id: UUID): Double =
        if ((physicalResistanceDown[id] ?: 0L) > plugin.ticks) 1.2 else 1.0

    private fun ready(target: LivingEntity, reaction: Reaction): Boolean =
        cooldowns.ready(target.uniqueId, "global", plugin.ticks) && cooldowns.ready(target.uniqueId, if (reaction.dendro) "dendro" else "classic", plugin.ticks)

    private fun mark(target: LivingEntity, reaction: Reaction) {
        val cfg = plugin.config
        val family = if (reaction.dendro) "dendro" else "classic"
        val prefix = if (reaction.dendro) "reactions.cooldown-dendro" else "reactions.cooldown"
        val suffix = if (target is Player) "players" else "entities"
        val ticks = cfg.getLong("$prefix-$suffix-ticks", cfg.getLong("$prefix-ticks", 35)).coerceIn(0, 1728000)
        cooldowns.mark(target.uniqueId, family, plugin.ticks, ticks)
        cooldowns.mark(target.uniqueId, "global", plugin.ticks, cfg.getLong("reactions.global-icd-ticks", 65).coerceIn(0, 1728000))
    }

    fun trigger(target: LivingEntity, reaction: Reaction, input: ReactionInput, sourceId: UUID?): Boolean {
        if (!ready(target, reaction) || !plugin.protection.canAffect(target, sourceId)) return false
        if (reaction == Reaction.ROOTS && !createArea(target, reaction, input, sourceId)) return false
        mark(target, reaction)
        plugin.holograms.reaction(target, reaction, input)
        val cfg = plugin.config
        when (reaction) {
            Reaction.LUNAR_CRYSTALLIZE, Reaction.LUNAR_BLOOM, Reaction.LUNAR_CHARGED,
            Reaction.STELLAR_CONDUCT, Reaction.STELLAR_SWIRL -> plugin.specialReactions.trigger(target, reaction, input, sourceId)
            Reaction.CRYSTALLIZE -> {
                val location = target.location.clone().add(0.0, 0.5, 0.0)
                val display = target.world.spawn(location, BlockDisplay::class.java) {
                    plugin.markTemporary(it)
                    it.block = Material.AMETHYST_BLOCK.createBlockData()
                    it.transformation = Transformation(Vector3f(-0.25f, -0.25f, -0.25f), Quaternionf(), Vector3f(0.5f), Quaternionf())
                    it.isGlowing = true
                }
                crystals += ShieldCrystal(display, (input.extra["attack_damage"] ?: 0.0) * org.plary.genshin.formula.DamageFormula.masteryBonus(input.extra["elemental_mastery"] ?: 0.0),
                    plugin.ticks + cfg.getLong("parameters.crystallize.duration-ticks", 100).coerceIn(1, 1728000))
            }
            Reaction.FREEZE -> {
                val duration = cfg.getInt("parameters.frozen.duration-ticks", 100).coerceIn(1, 1728000)
                target.freezeTicks = duration
                if (cfg.getBoolean("parameters.frozen.slowness.enabled", true)) effect(target, PotionEffectType.SLOWNESS,
                    cfg.getInt("parameters.frozen.slowness.duration-ticks", duration), cfg.getInt("parameters.frozen.slowness.amplifier", 255),
                    cfg.getBoolean("parameters.frozen.slowness.show-particles", false), cfg.getBoolean("parameters.frozen.slowness.show-icon", false))
                plugin.particles.burst(Element.CRYO, target.location, 20)
                target.world.playSound(target.location, Sound.BLOCK_GLASS_PLACE, 1f, 1f)
                plugin.damage.hit(target, reaction, input, sourceId)
            }
            Reaction.MELT, Reaction.VAPORIZE -> plugin.damage.hit(target, reaction, input, sourceId)
            Reaction.ELECTRO_CHARGED, Reaction.BURNING -> {
                periodic += PeriodicHit(target.uniqueId, target.world.uid, reaction, input, sourceId,
                    cfg.getInt("parameters.${reaction.key}.ticks", 5).coerceIn(0, 1200),
                    cfg.getLong("parameters.${reaction.key}.interval-ticks", 20).coerceIn(1, 1728000), plugin.ticks)
            }
            Reaction.SUPERCONDUCT -> {
                val duration = cfg.getInt("parameters.superconductor.duration-ticks", 100)
                physicalResistanceDown[target.uniqueId] = plugin.ticks + duration.coerceIn(1, 1728000)
                effect(target, PotionEffectType.SLOWNESS, duration, cfg.getInt("parameters.superconductor.slowness-amplifier", 1), true, true)
                effect(target, PotionEffectType.WEAKNESS, duration, cfg.getInt("parameters.superconductor.weakness-amplifier", 0), true, true)
                plugin.damage.hit(target, reaction, input, sourceId)
                plugin.particles.burst(Element.CRYO, target.location, 20)
            }
            Reaction.OVERLOAD, Reaction.SWIRL -> {
                val radius = cfg.numeric("parameters.${reaction.key}.radius", if (reaction == Reaction.SWIRL) 4.0 else 3.0).coerceIn(0.1, 64.0)
                val spread = if (input.a != Element.ANEMO) input.a else input.b
                val amount = input.amount(spread)
                val targets = (listOf(target) + plugin.protection.nearby(target.location, radius, sourceId, target is Player)).distinctBy { it.uniqueId }
                for (entity in targets) {
                    plugin.damage.hit(entity, reaction, input, sourceId)
                }
                if (reaction == Reaction.OVERLOAD) {
                    target.world.spawnParticle(Particle.EXPLOSION, target.location, 1)
                    target.world.playSound(target.location, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f)
                } else plugin.particles.ring(if (reaction == Reaction.SWIRL) Element.ANEMO else Element.CRYO, target.location, radius)
            }
            Reaction.QUICKEN -> {
                quicken[target.uniqueId] = Quicken(input.amount(Element.DENDRO), input.amount(Element.ELECTRO), plugin.ticks + cfg.getLong("parameters.quicken.duration-ticks", 200).coerceIn(1, 1728000), input.extra["elemental_mastery"] ?: 0.0, sourceId)
                plugin.damage.hit(target, reaction, input, sourceId)
            }
            Reaction.BLOOM -> plugin.blooms.spawn(target, input, sourceId)
            else -> Unit
        }
        return true
    }

    fun quickenReaction(target: LivingEntity, element: Element, amount: Int, sourceId: UUID?, modifier: ModifierState?, mastery: Double = amount.toDouble(), triggerSource: UUID? = sourceId): ReactionResult {
        val state = quicken[target.uniqueId]?.takeIf { it.expiresAt > plugin.ticks } ?: return ReactionResult.NONE
        val reaction = when (element) { Element.ELECTRO -> Reaction.AGGRAVATE; Element.DENDRO -> Reaction.SPREAD; else -> return ReactionResult.NONE }
        if (!ready(target, reaction)) return ReactionResult.BLOCKED
        val input = ReactionInput(Element.DENDRO, state.dendro, Element.ELECTRO, state.electro, modifier?.lunar ?: 0.0, modifier?.stellar ?: 0.0,
            mapOf((if (reaction == Reaction.AGGRAVATE) "new_electro" else "new_dendro") to amount.toDouble(), "elemental_mastery" to org.plary.genshin.formula.DamageFormula.reactionMastery(state.mastery, state.sourceId, mastery, triggerSource)))
        mark(target, reaction)
        quicken.remove(target.uniqueId)
        plugin.holograms.reaction(target, reaction, input)
        val radiusPath = if (reaction == Reaction.AGGRAVATE) "radius-aggravate" else "radius-spread"
        val targets = (listOf(target) + plugin.protection.nearby(target.location, plugin.config.numeric("parameters.quicken.$radiusPath", 4.0), sourceId, target is Player)).distinctBy { it.uniqueId }
        for (entity in targets) {
            plugin.damage.hit(entity, reaction, input, sourceId)
        }
        return ReactionResult.TRIGGERED
    }

    private fun createArea(target: LivingEntity, reaction: Reaction, input: ReactionInput, sourceId: UUID?): Boolean {
        val cfg = plugin.config
        val path = "parameters.${reaction.key}"
        val distance = cfg.numeric("$path.anti-stack.radius-min", cfg.numeric("reactions.anti-stack.radius-min", 2.0)).coerceAtLeast(0.0)
        val gap = cfg.getLong("$path.anti-stack.interval-ticks", cfg.getLong("reactions.anti-stack.interval-ticks", 20)).coerceAtLeast(0)
        if (areas.any { it.reaction == reaction && it.expiresAt > plugin.ticks && it.center.world == target.world &&
                it.center.distanceSquared(target.location) <= distance * distance && plugin.ticks - it.created < gap }) return false
        val radius = cfg.numeric("$path.${if (reaction == Reaction.CRYSTALLIZE) "radius-area" else "radius"}", 3.0).coerceIn(0.1, 64.0)
        val spread = if (reaction == Reaction.CRYSTALLIZE) (if (input.a != Element.GEO) input.a else input.b) else null
        areas += ReactionArea(target.location.clone(), reaction, input, sourceId, radius, target is Player, plugin.ticks,
            plugin.ticks + cfg.getLong("$path.duration-ticks", 100).coerceIn(1, 1728000),
            cfg.getLong("$path.interval-pulse-ticks", 10).coerceIn(1, 1728000), plugin.ticks, spread, spread?.let(input::amount) ?: 0)
        return true
    }

    fun tick() {
        for (crystal in crystals.toList()) {
            val display = crystal.display
            if (!display.isValid || crystal.expires <= plugin.ticks || !display.world.isChunkLoaded(display.location.blockX shr 4, display.location.blockZ shr 4)) {
                display.remove(); crystals.remove(crystal); continue
            }
            val player = display.world.getNearbyPlayers(display.location, 1.0).firstOrNull { it.isOnline && !it.isDead }
            if (player != null) {
                val maxAbsorption = player.getAttribute(Attribute.MAX_ABSORPTION)
                if (maxAbsorption != null && crystal.amount > 0.0) {
                    clearShield(player.uniqueId)
                    maxAbsorption.addTransientModifier(AttributeModifier(shieldKey, crystal.amount, AttributeModifier.Operation.ADD_NUMBER))
                    val previousAbsorption = player.absorptionAmount
                    player.absorptionAmount += crystal.amount
                    shields[player.uniqueId] = ActiveShield(previousAbsorption, plugin.ticks + plugin.config.getLong("parameters.crystallize.shield-duration-ticks", 200).coerceIn(1, 1728000))
                }
                display.remove(); crystals.remove(crystal)
            }
        }
        shields.toMap().forEach { (id, shield) ->
            val player = Bukkit.getPlayer(id)
            if (player == null || player.isDead || player.absorptionAmount <= 0.0 || shield.expires <= plugin.ticks) clearShield(id)
        }
        physicalResistanceDown.values.removeIf { it <= plugin.ticks }
        quicken.values.removeIf { it.expiresAt <= plugin.ticks }
        val due = periodic.filter { it.next <= plugin.ticks }.toList()
        for (hit in due) {
            val target = Bukkit.getEntity(hit.target) as? LivingEntity
            if (hit.remaining <= 0 || target == null || target.world.uid != hit.world || !plugin.protection.canAffect(target, hit.sourceId)) {
                periodic.remove(hit); continue
            }
            if (hit.reaction == Reaction.BURNING) {
                // Visual fire particles avoid adding unintended vanilla damage on top of the configured DoT.
                plugin.particles.burst(Element.PYRO, target.location, 10)
            } else {
                plugin.particles.burst(Element.ELECTRO, target.location, 10)
                effect(target, PotionEffectType.SLOWNESS, 20, 1)
            }
            plugin.damage.hit(target, hit.reaction, hit.input, hit.sourceId)
            hit.remaining--
            hit.next = plugin.ticks + hit.interval
            if (hit.remaining <= 0) periodic.remove(hit)
        }
        for (area in areas.toList()) {
            val world = area.center.world
            if (area.expiresAt <= plugin.ticks || world == null || !world.isChunkLoaded(area.center.blockX shr 4, area.center.blockZ shr 4)) {
                areas.remove(area); continue
            }
            if (area.next > plugin.ticks) continue
            area.next = plugin.ticks + area.interval
            if (area.reaction == Reaction.ROOTS) {
                val cfg = plugin.config
                val material = Material.matchMaterial(cfg.getString("parameters.roots.block-material", "ROOTED_DIRT")!!)
                    ?.takeIf { it.isBlock && !it.isAir } ?: Material.ROOTED_DIRT
                plugin.particles.roots(area.center, area.radius, cfg.getInt("parameters.roots.branches", 8),
                    cfg.numeric("parameters.roots.curve", 0.35), cfg.getString("parameters.roots.particle"), material, plugin.ticks)
            } else plugin.particles.ring(Element.GEO, area.center, area.radius)
            for (target in plugin.protection.nearby(area.center, area.radius, area.sourceId, area.players)) {
                plugin.damage.hit(target, area.reaction, area.input, area.sourceId)
                if (area.reaction == Reaction.ROOTS) effect(target, PotionEffectType.SLOWNESS,
                    plugin.config.getInt("parameters.roots.slowness-duration-ticks", 14), plugin.config.getInt("parameters.roots.slowness-amplifier", 1))
            }
        }
        if (plugin.ticks % 20L == 0L) cooldowns.cleanup(plugin.ticks)
    }

    private fun effect(target: LivingEntity, type: PotionEffectType, ticks: Int, amplifier: Int, particles: Boolean = false, icon: Boolean = false) {
        target.addPotionEffect(PotionEffect(type, ticks.coerceIn(1, 1728000), amplifier.coerceIn(0, 255), false, particles, icon))
    }

    private fun clearShield(id: UUID) {
        val shield = shields.remove(id) ?: return
        val player = Bukkit.getPlayer(id) ?: return
        player.absorptionAmount = minOf(player.absorptionAmount, shield.previousAbsorption)
        player.getAttribute(Attribute.MAX_ABSORPTION)?.removeModifier(shieldKey)
    }
    fun clear(id: UUID) { clearShield(id); physicalResistanceDown.remove(id); quicken.remove(id); cooldowns.clear(id); periodic.removeIf { it.target == id || it.sourceId == id }; areas.removeIf { it.sourceId == id } }
    fun clear() { shields.keys.toList().forEach(::clearShield); crystals.forEach { it.display.remove() }; crystals.clear(); physicalResistanceDown.clear(); quicken.clear(); cooldowns.clear(); periodic.clear(); areas.clear() }
}
