package org.plary.genshin.visual

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.block.data.BlockData
import org.plary.genshin.element.Element
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class Particles {
    fun configured(name: String?, fallback: Particle, location: Location, count: Int, material: Material = Material.ROOTED_DIRT) {
        val world = location.world ?: return
        val key = name?.uppercase(Locale.ROOT)?.let { if (it == "BLOCK_DUST" || it == "BLOCK_CRACK") "BLOCK" else it }
        val particle = runCatching { Particle.valueOf(key ?: fallback.name) }.getOrDefault(fallback)
            .takeIf { it.dataType == Void::class.java || it.dataType == BlockData::class.java } ?: fallback
        if (particle.dataType == BlockData::class.java)
            world.spawnParticle(particle, location, count, 0.05, 0.05, 0.05, 0.0, material.createBlockData())
        else world.spawnParticle(particle, location, count, 0.05, 0.05, 0.05, 0.0)
    }

    fun roots(center: Location, radius: Double, branches: Int, curve: Double, particle: String?, material: Material, tick: Long) {
        val random = Random(1234567L + tick / 2)
        repeat(branches.coerceIn(1, 32)) { branch ->
            var angle = 2 * Math.PI * branch / branches + random.nextDouble(-0.1, 0.1)
            val length = radius * random.nextDouble(0.85, 1.1)
            val steps = ceil(length * 6).toInt().coerceIn(6, 128)
            val location = center.clone().add(0.0, 0.05, 0.0)
            repeat(steps) {
                angle += (random.nextDouble() - 0.5) * curve.coerceIn(0.0, 2.0)
                location.add(cos(angle) * length / steps, 0.0, sin(angle) * length / steps)
                configured(particle, Particle.BLOCK, location, 1, material)
            }
        }
    }

    fun burst(element: Element, location: Location, count: Int = 8) {
        val world = location.world ?: return
        val particle = when (element) {
            Element.PYRO -> Particle.FLAME
            Element.HYDRO -> Particle.SPLASH
            Element.CRYO -> Particle.SNOWFLAKE
            Element.ELECTRO -> Particle.ELECTRIC_SPARK
            Element.DENDRO -> Particle.HAPPY_VILLAGER
            Element.GEO -> Particle.BLOCK
            Element.ANEMO -> Particle.CLOUD
            Element.ELEMENTAL -> Particle.END_ROD
        }
        if (element == Element.GEO) world.spawnParticle(particle, location, count, 0.25, 0.15, 0.25, 0.01, Material.STONE.createBlockData())
        else world.spawnParticle(particle, location, count, 0.25, 0.15, 0.25, 0.01)
    }

    fun ring(element: Element, center: Location, radius: Double) {
        repeat(24) { i ->
            val angle = 2 * Math.PI * i / 24
            burst(element, center.clone().add(cos(angle) * radius, 0.15, sin(angle) * radius), 1)
        }
    }
}
