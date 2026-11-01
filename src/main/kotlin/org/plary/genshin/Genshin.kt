package org.plary.genshin

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.plary.genshin.ambient.AmbientManager
import org.plary.genshin.combat.BloomManager
import org.plary.genshin.combat.DamageManager
import org.plary.genshin.combat.Protection
import org.plary.genshin.combat.ReactionsManager
import org.plary.genshin.combat.SpecialReactions
import org.plary.genshin.command.GenshinCommand
import org.plary.genshin.command.VisionRegions
import org.plary.genshin.element.ElementsManager
import org.plary.genshin.equipment.EquipmentManager
import org.plary.genshin.integration.GenshinExpansion
import org.plary.genshin.integration.Integrations
import org.plary.genshin.item.ItemManager
import org.plary.genshin.listener.PluginListener
import org.plary.genshin.region.RegionManager
import org.plary.genshin.visual.Holograms
import org.plary.genshin.visual.InfusionVisuals
import org.plary.genshin.visual.Particles
import java.io.File
import java.util.UUID
import java.util.logging.Level

open class Genshin : JavaPlugin() {
    private var settings = YamlConfiguration()
    lateinit var placeholderConfig: YamlConfiguration
        private set
    lateinit var classBonuses: YamlConfiguration
        private set
    val integrations by lazy { Integrations(this) }
    lateinit var items: ItemManager
        private set
    lateinit var equipment: EquipmentManager
        private set
    lateinit var protection: Protection
        private set
    lateinit var elements: ElementsManager
        private set
    lateinit var reactions: ReactionsManager
        private set
    lateinit var specialReactions: SpecialReactions
        private set
    lateinit var blooms: BloomManager
        private set
    lateinit var damage: DamageManager
        private set
    lateinit var ambient: AmbientManager
        private set
    lateinit var regions: RegionManager
        private set
    lateinit var infusionVisuals: InfusionVisuals
        private set
    lateinit var holograms: Holograms
        private set
    val particles = Particles()
    private val temporaryKey by lazy { NamespacedKey(this, "temporary-entity") }
    private var expansion: GenshinExpansion? = null
    private var started = false
    var ticks = 0L
        private set

    override fun getConfig(): FileConfiguration = settings

    override fun onLoad() { integrations.registerFlags() }

    override fun onEnable() {
        try {
            for (resource in listOf("config.yml", "items.yml", "placeholders.yml", "bonuselementperclass.yml", "regions/regions.yml")) {
                if (!File(dataFolder, resource).exists()) saveResource(resource, false)
            }
            items = ItemManager(this)
            equipment = EquipmentManager(this)
            protection = Protection(this)
            holograms = Holograms(this)
            infusionVisuals = InfusionVisuals(this)
            elements = ElementsManager(this)
            reactions = ReactionsManager(this)
            specialReactions = SpecialReactions(this)
            blooms = BloomManager(this)
            damage = DamageManager(this)
            ambient = AmbientManager(this)
            regions = RegionManager(this)
            reloadAll()
            Bukkit.getWorlds().flatMap { it.entities }.filter(::isTemporary).forEach { it.remove() }
            server.pluginManager.registerEvents(PluginListener(this), this)
            server.pluginManager.registerEvents(VisionRegions(this), this)
            val command = GenshinCommand(this)
            requireNotNull(getCommand("genshin")) { "Missing genshin command in plugin.yml" }.apply {
                setExecutor(command)
                tabCompleter = command
            }
            if (server.pluginManager.isPluginEnabled("PlaceholderAPI")) {
                expansion = GenshinExpansion(this).also { check(it.register()) { "Could not register PlaceholderAPI expansion" } }
            }
            started = true
            startTick()
            logger.info("Genshin enabled for Paper 1.21.11.")
        } catch (ex: Exception) {
            logger.log(Level.SEVERE, "Could not enable Genshin", ex)
            server.pluginManager.disablePlugin(this)
        }
    }

    /** Parse all YAML before replacing live configuration; malformed files leave the running config intact. */
    fun reloadAll() {
        val config = readYaml("config.yml")
        val itemConfig = readYaml("items.yml")
        val placeholders = readYaml("placeholders.yml")
        val classes = readYaml("bonuselementperclass.yml")
        val oldSettings = settings
        settings = config
        try { regions.load() } catch (ex: Exception) { settings = oldSettings; throw ex }
        if (started) {
            server.scheduler.cancelTasks(this)
            clearRuntime()
        }
        items.load(itemConfig)
        placeholderConfig = placeholders
        classBonuses = classes
        equipment.reload()
        damage.clear()
        if (config.getBoolean("efects.armor", true) && config.getBoolean("efects.artifact", false)) logger.warning("Both armor and artifacts are enabled; artifacts take priority.")
        if (started) startTick()
    }

    private fun readYaml(path: String): YamlConfiguration = YamlConfiguration().apply { load(File(dataFolder, path)) }

    private fun startTick() {
        server.scheduler.runTaskTimer(this, Runnable {
            ticks++
            elements.tick()
            reactions.tick()
            specialReactions.tick()
            blooms.tick()
            ambient.tick()
            if (ticks % 2L == 0L) holograms.tick()
            if (ticks % 5L == 0L) Bukkit.getOnlinePlayers().forEach(infusionVisuals::reconcile)
            if (ticks % 10L == 0L) Bukkit.getOnlinePlayers().forEach(infusionVisuals::particles)
            if (ticks % 20L == 0L) { equipment.cleanup(); damage.cleanup() }
        }, 1L, 1L)
    }

    fun markTemporary(entity: Entity) {
        entity.isPersistent = false
        entity.persistentDataContainer.set(temporaryKey, PersistentDataType.BYTE, 1.toByte())
    }

    fun isTemporary(entity: Entity): Boolean = entity.persistentDataContainer.has(temporaryKey, PersistentDataType.BYTE)

    fun clearEntity(id: UUID) {
        elements.clear(id); reactions.clear(id); specialReactions.clear(id); blooms.clear(id); damage.clear(id); equipment.clear(id)
    }

    private fun clearRuntime() {
        if (::blooms.isInitialized) blooms.clear()
        if (::elements.isInitialized) elements.clear()
        if (::reactions.isInitialized) reactions.clear()
        if (::specialReactions.isInitialized) specialReactions.clear()
        if (::holograms.isInitialized) holograms.clear()
        if (::equipment.isInitialized) equipment.clear()
        if (::damage.isInitialized) damage.clear()
        if (::ambient.isInitialized) ambient.clear()
    }

    override fun onDisable() {
        server.scheduler.cancelTasks(this)
        if (::infusionVisuals.isInitialized) Bukkit.getOnlinePlayers().forEach(infusionVisuals::clear)
        expansion?.unregister()
        expansion = null
        clearRuntime()
        Bukkit.getWorlds().flatMap { it.entities }.filter(::isTemporary).forEach { it.remove() }
        started = false
    }
}
