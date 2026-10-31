package org.plary.genshin.item

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.plary.genshin.Genshin
import org.plary.genshin.element.Element
import org.plary.genshin.util.*
import java.io.File
import java.util.UUID

data class Weapon(val element: Element, val amount: Int, val lunar: Double = 0.0, val stellar: Double = 0.0, val damageType: Element? = null, val baseDamage: Double? = null)
data class ItemDefinition(val id: String, val name: String, val weapon: Weapon?, val material: Material, val mastery: Int, val property: String)

class ItemManager(private val plugin: Genshin) {
    private val itemKey = NamespacedKey(plugin, "item-id")
    private var yaml = YamlConfiguration()
    var definitions: Map<String, ItemDefinition> = emptyMap()
        private set
    private var byName: Map<String, ItemDefinition> = emptyMap()

    fun load(config: YamlConfiguration) {
        val entries = config.getConfigurationSection("items")?.getKeys(false).orEmpty().associateWith { id ->
            val path = "items.$id"
            fun modifier(name: String): Double = if (config.flag("$path.$name.enabled"))
                config.numeric("$path.$name.bonus").coerceIn(0.0001, 1000000.0) else 0.0
            val lunar = modifier("lunar")
            val stellar = modifier("stellar")
            val typeName = requireNotNull(config.getString("$path.item-type")) { "Item '$id' requires item-type." }
            val damageType = Element.parse(typeName)?.takeIf { it != Element.ELEMENTAL }
            require(typeName.equals("physical", true) || damageType != null) { "Item '$id' has invalid item-type '$typeName'." }
            val property = requireNotNull(config.getString("$path.item-property")) { "Item '$id' requires item-property." }.lowercase(java.util.Locale.ROOT)
            require(property in setOf("weapon", "vision")) { "Item '$id' has invalid item-property '$property'." }
            require(property != "vision" || damageType != null) { "Vision '$id' must have an elemental item-type." }
            val mastery = roundedAmount(config.numeric("$path.elemental-mastery", 0.0))
            val damage = if (property == "weapon") number(config.get("$path.damage"), Double.NaN) else 0.0
            require(damage.isFinite() && damage in 0.0..2048.0) { "Item '$id' damage must be between 0 and 2048." }
            val weapon = Weapon(damageType ?: Element.ELEMENTAL, mastery,
                if (lunar >= stellar) lunar else 0.0, if (stellar > lunar) stellar else 0.0,
                damageType, if (property == "weapon") damage else null)
            val materialName = config.getString("$path.material", "BLAZE_ROD") ?: "BLAZE_ROD"
            val material = Material.matchMaterial(materialName)?.takeIf { it.isItem && !it.isAir }
                ?: throw IllegalArgumentException("Item '$id' has invalid material '$materialName'.")
            ItemDefinition(id, config.getString("$path.name", id)!!, weapon, material, mastery, property)
        }
        yaml = config
        definitions = entries
        byName = entries.values.associateBy { normalizedName(it.name) }
    }

    private fun definition(item: ItemStack?): ItemDefinition? {
        val meta = item?.itemMeta ?: return null
        val id = meta.persistentDataContainer.get(itemKey, PersistentDataType.STRING)
        if (id != null) return definitions[id]
        @Suppress("DEPRECATION")
        return if (meta.hasDisplayName()) byName[normalizedName(meta.displayName)] else null
    }

    fun weapon(item: ItemStack?): Weapon? = definition(item)?.takeIf { it.property == "weapon" }?.weapon

    /** The first vision in inventory slot order infuses a held weapon. */
    fun attack(source: LivingEntity, item: ItemStack?): Weapon? {
        val configured = definition(item)
        if (configured?.property == "vision") return null
        val material = item?.type ?: return null
        val vanillaWeapon = material.name.endsWith("_SWORD") || material.name.endsWith("_AXE") ||
            material in setOf(Material.BOW, Material.CROSSBOW, Material.TRIDENT, Material.MACE)
        if (configured == null && !vanillaWeapon) return null
        val base = configured?.weapon
        val vision = (source as? Player)?.inventory?.contents?.firstNotNullOfOrNull {
            definition(it)?.takeIf { candidate -> candidate.property == "vision" }?.weapon
        }
        if (vision != null) return vision.copy(baseDamage = base?.baseDamage)
        return base
    }

    fun create(id: String): ItemStack {
        val definition = requireNotNull(definitions[id]) { "Unknown item ID: $id" }
        val mastery = definition.mastery
        return ItemStack(definition.material).apply {
            editMeta {
                it.displayName(component(definition.name))
                it.persistentDataContainer.set(itemKey, PersistentDataType.STRING, id)
                val lore = yaml.getStringList("items.$id.lore").map(::component) + component("&7Elemental Mastery: &f$mastery")
                it.lore(lore)
                if (mastery > 50) it.setEnchantmentGlintOverride(true)
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
        updated.set("$path.material", item.type.name)
        updated.set("$path.item-type", "physical")
        updated.set("$path.item-property", "weapon")
        updated.set("$path.damage", 1.0)
        updated.set("$path.elemental-mastery", 0)
        for (modifier in listOf("lunar", "stellar")) {
            updated.set("$path.$modifier.enabled", false)
            updated.set("$path.$modifier.bonus", 0)
        }
        updated.save(File(plugin.dataFolder, "items.yml"))
        load(updated)
        return id
    }
}
