package org.plary.genshin.combat

import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.*
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import org.plary.genshin.Genshin
import org.plary.genshin.element.Element
import org.plary.genshin.element.Reaction
import org.plary.genshin.formula.ReactionInput
import org.plary.genshin.util.component
import org.plary.genshin.util.numeric
import java.util.Locale
import java.util.UUID

private data class BloomCore(val core: LivingEntity, val visual: BlockDisplay, val hologram: TextDisplay?, val input: ReactionInput, val sourceId: UUID?, val targetId: UUID, val expiresAt: Long)
private data class Missile(var location: Location, var velocity: Vector, var targetId: UUID?, val sourceId: UUID?, val input: ReactionInput, val expiresAt: Long, var next: Long)

class BloomManager(private val plugin: Genshin) {
    private val cores = mutableMapOf<UUID, BloomCore>()
    private val missiles = mutableListOf<Missile>()
    fun isCore(id: UUID): Boolean = id in cores

    fun spawn(target: LivingEntity, input: ReactionInput, sourceId: UUID?) {
        val cfg = plugin.config
        val direction = target.location.direction.setY(0.0)
        if (direction.lengthSquared() < 1e-6) direction.x = 1.0
        direction.normalize().multiply(cfg.numeric("parameters.bloom.spawn-offset", 1.2).coerceIn(0.0, 8.0))
        var location = target.location.add(direction)
        if (!location.block.isPassable) location = target.location
        val coreType = when (cfg.getString("parameters.bloom.core-type", "CHICKEN")?.uppercase(Locale.ROOT)) {
            "ZOMBIE" -> EntityType.ZOMBIE
            "SLIME" -> EntityType.SLIME
            else -> EntityType.CHICKEN
        }
        val core = target.world.spawnEntity(location, coreType) as LivingEntity
        plugin.markTemporary(core)
        core.isInvisible = true
        core.isSilent = true
        core.isCollidable = false
        core.setGravity(false)
        if (core is Mob) { core.setAI(false); core.removeWhenFarAway = false }
        if (core is Ageable) core.setBaby()
        if (core is Zombie) core.setBaby()
        if (core is Slime) core.size = 1
        val material = Material.matchMaterial(cfg.getString("parameters.bloom.visual-block", "OAK_LEAVES")!!)?.takeIf { it.isBlock } ?: Material.OAK_LEAVES
        val visual = target.world.spawn(location, BlockDisplay::class.java) {
            plugin.markTemporary(it)
            it.block = material.createBlockData()
            it.transformation = Transformation(Vector3f(-0.3f, 0f, -0.3f), Quaternionf(), Vector3f(0.6f), Quaternionf())
        }
        val duration = cfg.getLong("parameters.bloom.duration-ticks", 100).coerceIn(1, 1728000)
        val hologram = if (cfg.getBoolean("holograms.bloom.enabled", true))
            plugin.holograms.floating(location.clone().add(0.0, 0.9, 0.0), "", duration, core.uniqueId, 0.9) else null
        cores[core.uniqueId] = BloomCore(core, visual, hologram, input, sourceId, target.uniqueId, plugin.ticks + duration)
    }

    fun activate(id: UUID, element: Element, amount: Int, sourceId: UUID?, mastery: Double = amount.toDouble()): Boolean {
        val seed = cores[id] ?: return false
        if (element !in setOf(Element.PYRO, Element.ELECTRO) || amount <= 0) return false
        val source = sourceId ?: seed.sourceId
        val preferred = Bukkit.getEntity(seed.targetId) as? LivingEntity
        // A core cannot provide an alternate path around PvP or region protection.
        if (preferred != null && !plugin.protection.canAffect(preferred, source)) return false
        val reaction = if (element == Element.ELECTRO) Reaction.HYPERBLOOM else Reaction.BURGEON
        plugin.holograms.reaction(seed.core, reaction, seed.input, follow = false)
        val reactionMastery = org.plary.genshin.formula.DamageFormula.reactionMastery(seed.input.extra["elemental_mastery"] ?: 0.0, seed.sourceId, mastery, sourceId)
        val activated = seed.copy(input = seed.input.copy(extra = seed.input.extra + ("elemental_mastery" to reactionMastery)))
        if (element == Element.PYRO) explode(activated, Reaction.BURGEON, source)
        else {
            val input = activated.input.copy(extra = activated.input.extra + mapOf(
                "new_electro" to amount.toDouble(), "electro" to amount.toDouble(), "amount_electro" to amount.toDouble(),
                "sum" to (seed.input.amountA.toDouble() + seed.input.amountB + amount)))
            repeat(plugin.config.getInt("parameters.bloom.projectiles", 2).coerceIn(1, 32)) { index ->
                val initial = Vector(kotlin.math.cos(index * Math.PI), 0.5, kotlin.math.sin(index * Math.PI)).normalize()
                missiles += Missile(seed.core.location.add(0.0, 0.3, 0.0), initial, seed.targetId, source, input,
                    plugin.ticks + plugin.config.getLong("parameters.bloom.missile.life-ticks", 60).coerceIn(1, 1200), plugin.ticks)
            }
        }
        remove(id)
        return true
    }

    private fun explode(seed: BloomCore, reaction: Reaction, sourceId: UUID?) {
        val path = if (reaction == Reaction.BURGEON) "burgeon-radius" else "explosion-radius"
        val radius = plugin.config.numeric("parameters.bloom.$path", 3.5).coerceIn(0.1, 64.0)
        for (target in plugin.protection.nearby(seed.core.location, radius, sourceId)) {
            if (target is Player && !plugin.config.getBoolean("parameters.bloom.affect-players", true)) continue
            plugin.damage.hit(target, reaction, seed.input, sourceId)
        }
        seed.core.world.spawnParticle(Particle.EXPLOSION, seed.core.location, 2)
    }

    fun tick() {
        for ([id, seed] in cores.toMap()) {
            if (!seed.core.isValid || seed.core.isDead || !seed.visual.isValid) { remove(id); continue }
            if (plugin.ticks >= seed.expiresAt) { explode(seed, Reaction.BLOOM, seed.sourceId); remove(id); continue }
            seed.visual.teleport(seed.core.location)
            val text = plugin.config.getString("holograms.bloom.format", "&aBloom &7(%time%s)")!!
                .replace("%time%", String.format(Locale.ROOT, "%.1f", (seed.expiresAt - plugin.ticks) / 20.0))
            seed.hologram?.text(component(text))
            if (plugin.ticks % 6L == 0L) plugin.particles.burst(Element.DENDRO, seed.core.location, 3)
        }
        for (missile in missiles.toList()) {
            if (missile.next > plugin.ticks) continue
            val world = missile.location.world
            if (plugin.ticks >= missile.expiresAt || world == null || !world.isChunkLoaded(missile.location.blockX shr 4, missile.location.blockZ shr 4)) {
                missiles.remove(missile); continue
            }
            val cfg = plugin.config
            val period = cfg.getLong("parameters.bloom.missile.period-ticks", 2).coerceIn(1, 20)
            missile.next = plugin.ticks + period
            val searchRadius = cfg.numeric("parameters.bloom.missile.search-radius", 16.0).coerceIn(0.1, 64.0)
            var target = missile.targetId?.let(Bukkit::getEntity) as? LivingEntity
            if (target == null || target.world != world || !plugin.protection.canAffect(target, missile.sourceId) || target.location.distanceSquared(missile.location) > searchRadius * searchRadius) {
                target = plugin.protection.nearby(missile.location, searchRadius, missile.sourceId)
                    .filter { it !is Player || cfg.getBoolean("parameters.bloom.affect-players", true) }
                    .minByOrNull { it.location.distanceSquared(missile.location) }
                missile.targetId = target?.uniqueId
            }
            if (target == null) { missiles.remove(missile); continue }
            if (target is Player && !cfg.getBoolean("parameters.bloom.affect-players", true)) { missiles.remove(missile); continue }
            val destination = target.location.add(0.0, target.height * 0.5, 0.0)
            val toTarget = destination.toVector().subtract(missile.location.toVector())
            val turn = cfg.numeric("parameters.bloom.missile.turn-strength", 0.25).coerceIn(0.01, 1.0)
            if (toTarget.lengthSquared() > 1e-8) {
                val blended = missile.velocity.clone().normalize().multiply(1.0 - turn).add(toTarget.clone().normalize().multiply(turn))
                if (blended.lengthSquared() > 1e-8) missile.velocity = blended.normalize()
            }
            val speed = cfg.numeric("parameters.bloom.missile.speed", 1.0).coerceIn(0.01, 8.0)
            val distance = speed * period
            val hitRadius = cfg.numeric("parameters.bloom.missile.hit-radius", 0.6).coerceIn(0.1, 4.0)
            val entityHit = target.boundingBox.expand(hitRadius).rayTrace(missile.location.toVector(), missile.velocity, distance)
            val blockHit = world.rayTraceBlocks(missile.location, missile.velocity, distance, FluidCollisionMode.NEVER, true)
            val entityDistance = entityHit?.hitPosition?.distanceSquared(missile.location.toVector()) ?: Double.POSITIVE_INFINITY
            val blockDistance = blockHit?.hitPosition?.distanceSquared(missile.location.toVector()) ?: Double.POSITIVE_INFINITY
            if (entityDistance.isFinite() && entityDistance <= blockDistance) {
                val radius = cfg.numeric("parameters.bloom.hyperbloom-radius", 3.0).coerceIn(0.1, 64.0)
                val victims = if (target is Player) plugin.protection.nearby(target.location, radius, missile.sourceId, true) else listOf(target)
                victims.forEach { plugin.damage.hit(it, Reaction.HYPERBLOOM, missile.input, missile.sourceId) }
                missiles.remove(missile); continue
            }
            if (blockHit != null) { missiles.remove(missile); continue }
            missile.location.add(missile.velocity.clone().multiply(distance))
            plugin.particles.configured(cfg.getString("parameters.bloom.missile.particles.type1"), Particle.ELECTRIC_SPARK,
                missile.location, cfg.getInt("parameters.bloom.missile.particles.amount1", 3).coerceIn(0, 32))
            plugin.particles.configured(cfg.getString("parameters.bloom.missile.particles.type2"), Particle.END_ROD,
                missile.location, cfg.getInt("parameters.bloom.missile.particles.amount2", 1).coerceIn(0, 32))
        }
    }

    private fun remove(id: UUID) {
        val seed = cores.remove(id) ?: return
        seed.hologram?.remove(); seed.visual.remove(); seed.core.remove()
    }

    fun clear(id: UUID) {
        cores.filter { it.key == id || it.value.sourceId == id }.keys.toList().forEach(::remove)
        missiles.removeIf { it.sourceId == id }
    }

    fun clear() { cores.keys.toList().forEach(::remove); missiles.clear() }
}
