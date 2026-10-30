package org.plary.genshin.region

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.configuration.file.YamlConfiguration
import org.plary.genshin.Genshin
import java.io.File
import java.util.Locale
import java.util.UUID

data class RegionBox(val world: String, val minX: Int, val minY: Int, val minZ: Int, val maxX: Int, val maxY: Int, val maxZ: Int) {
    fun contains(loc: Location): Boolean = loc.world?.name == world && loc.blockX in minX..maxX && loc.blockY in minY..maxY && loc.blockZ in minZ..maxZ
}

class RegionManager(private val plugin: Genshin) {
    private var regions = emptyMap<String, RegionBox>()
    private val first = mutableMapOf<UUID, Location>()
    private val second = mutableMapOf<UUID, Location>()

    fun file(): File {
        val directory = File(plugin.dataFolder, "regions").canonicalFile
        val file = File(directory, plugin.config.getString("regions.file", "regions.yml")!!).canonicalFile
        require(file.toPath().startsWith(directory.toPath()) && file != directory) { "regions.file must stay inside the regions directory" }
        return file
    }

    fun load() {
        val cfg = YamlConfiguration()
        if (file().exists()) cfg.load(file())
        regions = cfg.getConfigurationSection("regions")?.getKeys(false).orEmpty().associateWith { name ->
            val path = "regions.$name"
            val x1 = cfg.getInt("$path.pos1.x"); val x2 = cfg.getInt("$path.pos2.x")
            val y1 = cfg.getInt("$path.pos1.y"); val y2 = cfg.getInt("$path.pos2.y")
            val z1 = cfg.getInt("$path.pos1.z"); val z2 = cfg.getInt("$path.pos2.z")
            RegionBox(requireNotNull(cfg.getString("$path.world")), minOf(x1, x2), minOf(y1, y2), minOf(z1, z2), maxOf(x1, x2), maxOf(y1, y2), maxOf(z1, z2))
        }
    }

    fun select(player: Player, pos: Int) { (if (pos == 1) first else second)[player.uniqueId] = player.location.clone() }
    fun forget(id: UUID) { first.remove(id); second.remove(id) }

    fun create(player: Player, rawName: String) {
        val name = rawName.lowercase(Locale.ROOT)
        require(name.matches(Regex("[a-z0-9_-]{1,64}"))) { "Region names may contain letters, numbers, underscores and hyphens." }
        require(name !in regions) { "A region with this name already exists." }
        val a = requireNotNull(first[player.uniqueId]) { "Set pos1 first." }
        val b = requireNotNull(second[player.uniqueId]) { "Set pos2 first." }
        require(a.world == b.world) { "Both positions must be in the same world." }
        val world = requireNotNull(a.world)
        val box = RegionBox(world.name, minOf(a.blockX, b.blockX), world.minHeight, minOf(a.blockZ, b.blockZ), maxOf(a.blockX, b.blockX), world.maxHeight - 1, maxOf(a.blockZ, b.blockZ))
        val updated = regions + (name to box)
        val cfg = YamlConfiguration()
        updated.forEach { (id, r) ->
            val path = "regions.$id"
            cfg.set("$path.world", r.world)
            cfg.set("$path.pos1", mapOf("x" to r.minX, "y" to r.minY, "z" to r.minZ))
            cfg.set("$path.pos2", mapOf("x" to r.maxX, "y" to r.maxY, "z" to r.maxZ))
        }
        file().parentFile.mkdirs()
        cfg.save(file())
        regions = updated
    }

    fun ambientAllowed(location: Location, event: String): Boolean {
        if (!plugin.config.getBoolean("regions.restrictions.enabled", true)) return true
        if (plugin.integrations.ambientForced(location, event)) return true
        if (plugin.config.getBoolean("regions.restrictions.custom-default-block", true) && regions.values.any { it.contains(location) }) return false
        return plugin.integrations.ambientAllowed(location, event)
    }
}
