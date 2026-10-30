package org.plary.genshin.combat

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Color
import org.bukkit.Sound
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.WeatherType
import org.bukkit.attribute.Attribute
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Item
import org.bukkit.inventory.ItemStack
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import org.plary.genshin.Genshin
import org.plary.genshin.element.Element
import org.plary.genshin.element.Reaction
import org.plary.genshin.formula.ReactionInput
import org.plary.genshin.util.numeric
import java.util.UUID

private data class SpecialEffect(val reaction: Reaction, val center: Location, val target: UUID, val source: UUID?,
    val input: ReactionInput, val expires: Long, var next: Long, var level: Int = 1,
    val displays: List<BlockDisplay> = emptyList(), val bloom: Item? = null, val created: Long = 0)
private data class CrystalShot(val location: Location, val target: UUID, val source: UUID?, val input: ReactionInput, val expires: Long)

class SpecialReactions(private val plugin: Genshin) {
    private val effects = mutableListOf<SpecialEffect>()
    private val shots = mutableListOf<CrystalShot>()

    fun trigger(target: LivingEntity, reaction: Reaction, input: ReactionInput, source: UUID?) {
        val path = "parameters.${reaction.key}"
        val duration = plugin.config.getLong("$path.duration-ticks", 100).coerceIn(1, 12000)
        val center = target.location.clone()
        if (reaction == Reaction.STELLAR_SWIRL) {
            plugin.damage.hit(target, reaction, input, source)
            val radius = plugin.config.numeric("$path.merge-radius", 4.0).coerceIn(0.1, 64.0)
            val vortex = effects.firstOrNull { it.reaction == reaction && it.source == source && it.center.world == center.world && it.center.distanceSquared(center) <= radius * radius }
            if (vortex != null) { vortex.level = (vortex.level + 1).coerceAtMost(3); return }
        }
        val displays = when (reaction) {
            Reaction.LUNAR_CRYSTALLIZE -> (0..2).map { i -> display(center.clone().add(kotlin.math.cos(i * Math.PI * 2 / 3) * 3.0, 1.5, kotlin.math.sin(i * Math.PI * 2 / 3) * 3.0), Material.AMETHYST_BLOCK) }
            else -> emptyList()
        }
        val bloom = if (reaction == Reaction.LUNAR_BLOOM) {
            val direction = target.location.direction.setY(0.0).let { if (it.lengthSquared() < 0.01) Vector(1.0, 0.0, 0.0) else it.normalize() }
            target.world.dropItem(center.clone().add(0.0, target.height * 0.6, 0.0), ItemStack(Material.SLIME_BALL)) {
                plugin.markTemporary(it)
                it.isGlowing = true
                it.pickupDelay = Int.MAX_VALUE
                it.velocity = direction.multiply(0.45).setY(0.55)
            }
        } else null
        effects += SpecialEffect(reaction, center, target.uniqueId, source, input, plugin.ticks + duration, plugin.ticks, displays = displays, bloom = bloom, created = plugin.ticks)
    }

    private fun display(location: Location, material: Material): BlockDisplay = location.world!!.spawn(location, BlockDisplay::class.java) {
        plugin.markTemporary(it)
        it.block = material.createBlockData()
        it.transformation = Transformation(Vector3f(-0.3f, -0.3f, -0.3f), Quaternionf(), Vector3f(0.6f), Quaternionf())
    }

    fun physicalMultiplier(target: LivingEntity): Double = if (effects.any {
        it.reaction == Reaction.STELLAR_CONDUCT && it.expires > plugin.ticks && it.source != target.uniqueId &&
            it.center.world == target.world && it.center.distanceSquared(target.location) <= radius(it) * radius(it) && plugin.protection.canAffect(target, it.source)
    }) 1.2 else 1.0

    private fun radius(effect: SpecialEffect): Double = plugin.config.numeric("parameters.${effect.reaction.key}.radius", 4.0).coerceIn(0.1, 64.0)
    private fun strike(target: LivingEntity, effect: SpecialEffect) {
        target.world.strikeLightningEffect(target.location)
        plugin.damage.hit(target, effect.reaction, effect.input, effect.source)
    }

    fun tick() {
        for (shot in shots.toList()) {
            val target = Bukkit.getEntity(shot.target) as? LivingEntity
            if (shot.expires <= plugin.ticks || target == null || target.world != shot.location.world || !plugin.protection.canAffect(target, shot.source)) {
                shots.remove(shot); continue
            }
            val delta: Vector = target.location.add(0.0, target.height / 2, 0.0).subtract(shot.location).toVector()
            if (delta.lengthSquared() <= 1.0) {
                plugin.damage.hit(target, Reaction.LUNAR_CRYSTALLIZE, shot.input, shot.source, ignoreCooldown = true)
                shots.remove(shot)
            } else {
                shot.location.add(delta.normalize().multiply(0.8))
                shot.location.world?.spawnParticle(Particle.END_ROD, shot.location, 2, 0.05, 0.05, 0.05, 0.0)
            }
        }
        for (effect in effects.toList()) {
            val world = effect.center.world
            if (world == null || !world.isChunkLoaded(effect.center.blockX shr 4, effect.center.blockZ shr 4)) { remove(effect); continue }
            if (effect.expires <= plugin.ticks) {
                if (effect.reaction == Reaction.STELLAR_SWIRL) {
                    val radius = radius(effect) * (1.0 + 0.5 * (effect.level - 1))
                    world.spawnParticle(Particle.EXPLOSION_EMITTER, effect.center.clone().add(0.0, 1.0, 0.0), 1)
                    world.playSound(effect.center, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.8f)
                    plugin.particles.burst(Element.ANEMO, effect.center.clone().add(0.0, 1.0, 0.0), 80)
                    for (target in plugin.protection.nearby(effect.center, radius, effect.source))
                        plugin.damage.hit(target, effect.reaction, effect.input.copy(extra = effect.input.extra + ("special_scale" to effect.level.toDouble())), effect.source)
                }
                remove(effect); continue
            }
            if (effect.reaction == Reaction.LUNAR_BLOOM) {
                val item = effect.bloom
                if (item == null || !item.isValid || item.world != world) { remove(effect); continue }
                val location = item.location
                world.spawnParticle(Particle.DUST, location, 8, 0.3, 0.3, 0.3, 0.0, Particle.DustOptions(Color.LIME, 1.2f))
                val player = if (plugin.ticks - effect.created >= 20) world.getNearbyPlayers(location, 1.0).firstOrNull { !it.isDead && it.isOnline } else null
                if (player != null) {
                    val max = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                    player.health = (player.health + plugin.config.numeric("parameters.lunar-bloom.heal", 6.0).coerceAtLeast(0.0)).coerceAtMost(max)
                    remove(effect)
                }
                continue
            }
            if (effect.reaction == Reaction.STELLAR_SWIRL && plugin.ticks % 2L == 0L) {
                val size = radius(effect) * (1.0 + 0.5 * (effect.level - 1))
                val angle = plugin.ticks * 0.25
                for (i in 0 until 12) {
                    val a = angle + i * Math.PI * 2 / 12
                    val point = effect.center.clone().add(kotlin.math.cos(a) * size * 0.6, 0.3 + (i % 4) * 0.5, kotlin.math.sin(a) * size * 0.6)
                    world.spawnParticle(Particle.CLOUD, point, 1, 0.08, 0.08, 0.08, 0.02)
                }
            }
            if (effect.next > plugin.ticks) continue
            val path = "parameters.${effect.reaction.key}"
            effect.next = plugin.ticks + plugin.config.getLong("$path.interval-ticks", 20).coerceIn(1, 12000)
            when (effect.reaction) {
                Reaction.LUNAR_CRYSTALLIZE -> {
                    val target = Bukkit.getEntity(effect.target) as? LivingEntity
                    if (target == null || target.world != world || !plugin.protection.canAffect(target, effect.source)) { remove(effect); continue }
                    for (display in effect.displays) {
                        val origin = display.location.add(0.0, 0.3, 0.0)
                        shots += CrystalShot(origin, target.uniqueId, effect.source, effect.input, plugin.ticks + 60)
                    }
                }
                Reaction.LUNAR_CHARGED -> {
                    val target = Bukkit.getEntity(effect.target) as? LivingEntity
                    if (target == null || target.world != world || !plugin.protection.canAffect(target, effect.source)) { remove(effect); continue }
                    if (target is Player) target.setPlayerWeather(WeatherType.DOWNFALL)
                    strike(target, effect)
                }
                Reaction.STELLAR_CONDUCT -> for (target in plugin.protection.nearby(effect.center, radius(effect), effect.source)) strike(target, effect)
                Reaction.STELLAR_SWIRL -> plugin.particles.ring(Element.ANEMO, effect.center, radius(effect) * (1.0 + 0.5 * (effect.level - 1)))
                else -> Unit
            }
        }
    }
    private fun remove(effect: SpecialEffect) {
        effect.displays.forEach { it.remove() }
        effect.bloom?.remove()
        if (effect.reaction == Reaction.LUNAR_CHARGED && effects.none { it !== effect && it.reaction == Reaction.LUNAR_CHARGED && it.target == effect.target })
            (Bukkit.getEntity(effect.target) as? Player)?.resetPlayerWeather()
        effects.remove(effect)
    }
    fun clear(id: UUID) { effects.filter { it.target == id || it.source == id }.toList().forEach(::remove); shots.removeIf { it.target == id || it.source == id } }
    fun clear() { effects.toList().forEach(::remove); shots.clear() }
}
