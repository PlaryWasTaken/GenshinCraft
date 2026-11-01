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

data class Weapon(val element: Element, val amount: Int, val lunar: Double = 0.0, val stellar: Double = 0.0, val damageType: Element? = null, val baseDamage: Double? = null, val elementalDamagePercent: Double = 0.0)
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
            val bonus = number(config.get("$path.elemental-damage-percent"), if (config.contains("$path.elemental-damage-percent")) Double.NaN else 0.0)
            require(bonus in 0.0..1000.0) { "Item '$id' elemental-damage-percent must be between 0 and 1000." }
            val weapon = Weapon(damageType ?: Element.ELEMENTAL, mastery,
                if (lunar >= stellar) lunar else 0.0, if (stellar > lunar) stellar else 0.0,
                damageType, if (property == "weapon") damage else null, bonus)
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

    fun regionVisionElement(item: ItemStack?): Element? {
        val def = definition(item) ?: return null
        val element = def.weapon?.damageType ?: return null
        return element.takeIf { def.property == "vision" && def.id == "vision_${element.key}" }
    }

    fun isPluginItem(item: ItemStack?): Boolean =
        item?.itemMeta?.persistentDataContainer?.has(itemKey, PersistentDataType.STRING) == true

    fun weapon(item: ItemStack?): Weapon? = definition(item)?.takeIf { it.property == "weapon" }?.weapon

    /** The first vision in inventory slot order determines the active infusion. */
    fun vision(player: Player): Weapon? = player.inventory.contents.firstNotNullOfOrNull {
        definition(it)?.takeIf { candidate -> candidate.property == "vision" }?.weapon
    }

    fun isWeapon(item: ItemStack?): Boolean {
        val configured = definition(item)
        if (configured != null) return configured.property == "weapon"
        val material = item?.type ?: return false
        return material.name.endsWith("_SWORD") || material.name.endsWith("_AXE") ||
            material in setOf(Material.BOW, Material.CROSSBOW, Material.TRIDENT, Material.MACE)
    }

    fun attack(source: LivingEntity, item: ItemStack?): Weapon? {
        if (!isWeapon(item)) return null
        val base = weapon(item)
        if (base?.damageType != null) return base
        val vision = (source as? Player)?.let(::vision)
        if (vision != null) return vision.copy(baseDamage = base?.baseDamage, elementalDamagePercent = (vision.elementalDamagePercent + (base?.elementalDamagePercent ?: 0.0)).coerceAtMost(1000.0))
        return base
    }

    fun create(id: String): ItemStack {
        val definition = requireNotNull(definitions[id]) { "Unknown item ID: $id" }
        val mastery = definition.mastery
        return ItemStack(definition.material).apply {
            editMeta {
                it.displayName(component(definition.name))
                it.persistentDataContainer.set(itemKey, PersistentDataType.STRING, id)
                it.setMaxStackSize(1)
                val strings = if (yaml.contains("items.$id.custom-model-data.strings"))
                    yaml.getStringList("items.$id.custom-model-data.strings")
                else if (id == "vision_${definition.weapon?.damageType?.key}")
                    listOf("${definition.weapon?.damageType?.key}_mondstadt") else emptyList()
                if (strings.isNotEmpty()) {
                    val model = it.customModelDataComponent
                    model.strings = strings
                    it.setCustomModelDataComponent(model)
                }
                val lore = yaml.getStringList("items.$id.lore").map(::component).toMutableList()
                lore += component("&7Elemental Mastery: &f$mastery")
                val bonus = definition.weapon?.elementalDamagePercent ?: 0.0
                if (yaml.contains("items.$id.elemental-damage-percent")) lore += component("&7Elemental Damage: &f+${if (bonus == bonus.toLong().toDouble()) bonus.toLong().toString() else bonus.toString()}%")
                if (definition.property == "vision") {
                    if ((definition.weapon?.lunar ?: 0.0) > 0) lore += component("&bMoonlit")
                    if ((definition.weapon?.stellar ?: 0.0) > 0) lore += component("&dStellar")
                }
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
        updated.set("$path.elemental-damage-percent", 0)
        for (modifier in listOf("lunar", "stellar")) {
            updated.set("$path.$modifier.enabled", false)
            updated.set("$path.$modifier.bonus", 0)
        }
        updated.save(File(plugin.dataFolder, "items.yml"))
        load(updated)
        return id
    }
}
