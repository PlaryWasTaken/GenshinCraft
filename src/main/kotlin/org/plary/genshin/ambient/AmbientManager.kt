package org.plary.genshin.ambient

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.plary.genshin.Genshin
import org.plary.genshin.element.Element
import org.plary.genshin.util.numeric
import kotlin.math.abs
import kotlin.random.Random

class AmbientManager(private val plugin: Genshin) {
    private val nextEvent = mutableMapOf<String, Long>()

    fun apply(entity: LivingEntity, element: Element, source: String): Boolean {
        val cfg = plugin.config
        val amount = cfg.getInt("ambient.amounts.${element.key}.$source", cfg.getInt("ambient.amounts.${element.key}.default", cfg.getInt("ambient.default-amount", 2)))
        return plugin.elements.apply(entity, element, amount, ambient = true, adjustBonus = false)
    }

    fun scan(entity: LivingEntity) {
        if (!plugin.protection.eligible(entity)) return
        val location = entity.location
        val feet = location.block.type
        val head = entity.eyeLocation.block.type
        val below = location.clone().subtract(0.0, 0.2, 0.0).block.type
        when {
            feet == Material.LAVA -> apply(entity, Element.PYRO, "lava")
            entity.fireTicks > 0 || feet == Material.FIRE || feet == Material.SOUL_FIRE -> apply(entity, Element.PYRO, "fire")
            feet == Material.POWDER_SNOW || head == Material.POWDER_SNOW -> apply(entity, Element.CRYO, "powder-snow")
            below == Material.SNOW || below == Material.SNOW_BLOCK -> apply(entity, Element.CRYO, "snow-block")
            entity.isInWater || feet in setOf(Material.WATER, Material.KELP, Material.KELP_PLANT, Material.SEAGRASS, Material.TALL_SEAGRASS) -> apply(entity, Element.HYDRO, "water")
            entity.world.environment == World.Environment.NORMAL && entity.world.hasStorm() && location.block.lightFromSky == 15.toByte() -> {
                val biome = location.block.biome.key.key
                val dry = biome.contains("desert") || biome.contains("badlands") || biome.contains("savanna")
                if (!dry) {
                    if (location.block.temperature < 0.15) apply(entity, Element.CRYO, "snow")
                    else apply(entity, Element.HYDRO, "rain")
                }
            }
        }
    }

    fun fall(entity: LivingEntity) {
        val material = entity.location.block.type.takeUnless { it.isAir } ?: entity.location.subtract(0.0, 0.3, 0.0).block.type
        when {
            material in setOf(Material.SNOW, Material.SNOW_BLOCK, Material.POWDER_SNOW) -> apply(entity, Element.CRYO, "fall-snow")
            material == Material.GRASS_BLOCK -> apply(entity, Element.DENDRO, "fall-grass")
            material == Material.HAY_BLOCK -> apply(entity, Element.DENDRO, "fall-hay")
            material == Material.MYCELIUM -> apply(entity, Element.DENDRO, "fall-mycelium")
            material.name.endsWith("_LEAVES") -> apply(entity, Element.DENDRO, "fall-leaves")
            material in setOf(Material.STONE, Material.COBBLESTONE, Material.DIORITE, Material.ANDESITE, Material.GRANITE) -> apply(entity, Element.GEO, "fall-${material.name.lowercase()}")
        }
    }

    fun tick() {
        val period = plugin.config.getLong("ambient.scan-period-ticks", 5).coerceIn(1, 1200)
        if (plugin.ticks % period != 0L) return
        for (world in Bukkit.getWorlds()) {
            val players = world.players
            if (players.isEmpty()) continue
            val chunks = players.map { it.location.blockX shr 4 to (it.location.blockZ shr 4) }
            for (entity in world.livingEntities) {
                val location = entity.location
                if (chunks.any { (x, z) -> abs((location.blockX shr 4) - x) <= 8 && abs((location.blockZ shr 4) - z) <= 8 }) scan(entity)
            }
        }
        event("geo-storm", Element.GEO, 14.0)
        event("dendro-breeze", Element.DENDRO, 12.0)
        event("pyro-heat", Element.PYRO, 10.0)
    }

    private fun event(name: String, element: Element, defaultRadius: Double) {
        if ((nextEvent[name] ?: 0L) > plugin.ticks) return
        val cfg = plugin.config
        if (Random.nextDouble() >= cfg.numeric("ambient.$name.chance", 0.02).coerceIn(0.0, 1.0)) return
        val biomes = cfg.getStringList("ambient.$name.biomes").map { it.lowercase() }.toSet()
        for (player in Bukkit.getOnlinePlayers()) {
            if (!plugin.protection.eligible(player) || !plugin.regions.ambientAllowed(player.location, name)) continue
            if (!plugin.integrations.ambientForced(player.location, name)) {
                if (name == "pyro-heat") { if (player.world.environment != World.Environment.NETHER) continue }
                else if (player.location.block.biome.key.key !in biomes) continue
            }
            val radius = cfg.numeric("ambient.$name.radius", defaultRadius).coerceIn(0.1, 64.0)
            val targets = plugin.protection.nearby(player.location, radius, null)
            val amount = cfg.getInt("ambient.$name.amount", 1)
            for (target in targets) {
                if (!plugin.regions.ambientAllowed(target.location, name)) continue
                if (name == "pyro-heat" && target.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE)) continue
                val applied = plugin.elements.apply(target, element, amount, ambient = true, adjustBonus = false)
                if (applied && name == "geo-storm") target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS,
                    cfg.getInt("ambient.geo-storm.slowness-duration-ticks", 60).coerceIn(1, 1728000),
                    cfg.getInt("ambient.geo-storm.slowness-amplifier", 1).coerceIn(0, 255), true, false, true))
            }
            plugin.particles.ring(element, player.location, radius)
            nextEvent[name] = plugin.ticks + cfg.getLong("ambient.$name.cooldown-ticks", 160).coerceIn(1, 1728000)
            break
        }
    }

    fun clear() { nextEvent.clear() }
}
