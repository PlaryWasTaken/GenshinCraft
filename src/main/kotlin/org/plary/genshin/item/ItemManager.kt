package org.plary.genshin.item

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.plary.genshin.Genshin
import org.plary.genshin.element.Element
import org.plary.genshin.util.*
import java.io.File
import java.util.Locale
import java.util.UUID

data class Weapon(val element: Element, val amount: Int, val lunar: Double = 0.0, val stellar: Double = 0.0, val damageType: Element? = null)
data class ItemDefinition(val id: String, val name: String, val weapon: Weapon?)

class ItemManager(private val plugin: Genshin) {
    private val itemKey = NamespacedKey(plugin, "item-id")
    private var yaml = YamlConfiguration()
    var definitions: Map<String, ItemDefinition> = emptyMap()
        private set
    private var byName: Map<String, ItemDefinition> = emptyMap()

    fun load(config: YamlConfiguration) {
        val entries = config.getConfigurationSection("items")?.getKeys(false).orEmpty().associateWith { id ->
            val path = "items.$id"
            val enabled = Element.entries.filter { config.flag("$path.elements.${it.key}.enabled") }
            if (enabled.size > 1) plugin.logger.warning("Item '$id' has multiple elements enabled; using ${enabled.last().key}.")
            val element = enabled.lastOrNull()
            val lunar = if (config.flag("$path.elements.lunar.enabled")) config.numeric("$path.elements.lunar.bonus").coerceAtLeast(0.0001) else 0.0
            val stellar = if (config.flag("$path.elements.stellar.enabled")) config.numeric("$path.elements.stellar.bonus").coerceAtLeast(0.0001) else 0.0
            val damageTypeName = config.getString("$path.damage-type", "physical")!!
            val damageType = Element.parse(damageTypeName)?.takeIf { it != Element.ELEMENTAL }
            if (!damageTypeName.equals("physical", true) && damageType == null) plugin.logger.warning("Item '$id' has invalid damage-type '$damageTypeName'; using physical.")
            val weapon = (element ?: damageType)?.let {
                Weapon(it, roundedAmount(config.numeric("$path.elements.${it.key}.amount")),
                    if (lunar >= stellar) lunar else 0.0, if (stellar > lunar) stellar else 0.0,
                    damageType)
            }
            ItemDefinition(id, config.getString("$path.name", id)!!, weapon)
        }
        yaml = config
        definitions = entries
        byName = entries.values.associateBy { normalizedName(it.name) }
    }

    fun weapon(item: ItemStack?): Weapon? {
        val meta = item?.itemMeta ?: return null
        val id = meta.persistentDataContainer.get(itemKey, PersistentDataType.STRING)
        if (id != null) return definitions[id]?.weapon
        @Suppress("DEPRECATION")
        return if (meta.hasDisplayName()) byName[normalizedName(meta.displayName)]?.weapon else null
    }

    fun create(id: String, type: String): ItemStack {
        val definition = requireNotNull(definitions[id]) { "Unknown item ID: $id" }
        val material = when (type.lowercase(Locale.ROOT)) {
            "sword" -> Material.DIAMOND_SWORD
            "bow" -> Material.BOW
            "axe" -> Material.DIAMOND_AXE
            "trident" -> Material.TRIDENT
            "staff" -> Material.BLAZE_ROD
            else -> Material.matchMaterial(type)
        }
        require(material != null && material.isItem && !material.isAir) { "Invalid item material: $type" }
        return ItemStack(material).apply {
            editMeta {
                it.displayName(component(definition.name))
                it.persistentDataContainer.set(itemKey, PersistentDataType.STRING, id)
                val lore = yaml.getStringList("items.$id.lore")
                if (lore.isNotEmpty()) it.lore(lore.map(::component))
            }
        }
    }

    fun register(item: ItemStack): String {
        val meta = item.itemMeta
        require(meta != null && meta.hasDisplayName() && !item.type.isAir) { "Hold a named item in your main hand." }
        @Suppress("DEPRECATION") val name = meta.displayName
        require(normalizedName(name) !in byName) { "This display name is already registered." }
        val id = "item_${UUID.randomUUID().toString().take(8)}"
        val path = "items.$id"
        val updated = YamlConfiguration().apply { loadFromString(yaml.saveToString()) }
        updated.set("$path.name", name)
        updated.set("$path.damage-type", "physical")
        Element.entries.forEach {
            updated.set("$path.elements.${it.key}.enabled", false)
            updated.set("$path.elements.${it.key}.amount", 0)
        }
        for (modifier in listOf("lunar", "stellar")) {
            updated.set("$path.elements.$modifier.enabled", false)
            updated.set("$path.elements.$modifier.bonus", 0)
        }
        updated.save(File(plugin.dataFolder, "items.yml"))
        load(updated)
        return id
    }
}
