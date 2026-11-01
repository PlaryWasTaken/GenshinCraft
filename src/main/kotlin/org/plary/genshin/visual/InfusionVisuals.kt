package org.plary.genshin.visual

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.plary.genshin.Genshin
import org.plary.genshin.element.Element

/** Keeps the temporary lore in sync with the same vision selection used for attacks. */
class InfusionVisuals(private val plugin: Genshin) {
    private val infusionKey = NamespacedKey(plugin, "infusion-lore")
    private val particlePreferenceKey = NamespacedKey(plugin, "weapon-particles-disabled")

    fun particlesEnabled(player: Player): Boolean = !player.persistentDataContainer.has(particlePreferenceKey, PersistentDataType.BYTE)

    fun setParticlesEnabled(player: Player, enabled: Boolean) {
        if (enabled) player.persistentDataContainer.remove(particlePreferenceKey)
        else player.persistentDataContainer.set(particlePreferenceKey, PersistentDataType.BYTE, 1.toByte())
    }

    private fun line(element: Element): Component {
        val color = when (element) {
            Element.PYRO -> NamedTextColor.RED
            Element.HYDRO -> NamedTextColor.BLUE
            Element.CRYO -> NamedTextColor.AQUA
            Element.ELECTRO -> NamedTextColor.DARK_PURPLE
            Element.DENDRO -> NamedTextColor.DARK_GREEN
            Element.GEO -> NamedTextColor.GOLD
            Element.ANEMO -> NamedTextColor.WHITE
            Element.ELEMENTAL -> NamedTextColor.GRAY
        }
        return Component.text("Infused with ${element.displayName}", color).decoration(TextDecoration.ITALIC, false)
    }

    /** Returns a replacement only when the lore or marker needs to change. */
    fun update(item: ItemStack?, element: Element?): ItemStack? {
        if (item == null || item.type.isAir) return null
        val meta = item.itemMeta ?: return null
        val stored = meta.persistentDataContainer.get(infusionKey, PersistentDataType.STRING)
        val previous = Element.parse(stored)
        val lore = meta.lore()?.toMutableList() ?: mutableListOf()
        if (stored == element?.key && (element == null || lore.contains(line(element)))) return null
        if (previous != null) {
            val index = lore.lastIndexOf(line(previous))
            if (index >= 0) lore.removeAt(index)
        }
        if (element != null) {
            lore.add(line(element))
            meta.persistentDataContainer.set(infusionKey, PersistentDataType.STRING, element.key)
        } else meta.persistentDataContainer.remove(infusionKey)
        meta.lore(lore.takeIf { it.isNotEmpty() })
        return item.clone().apply { itemMeta = meta }
    }

    fun clean(inventory: Inventory) {
        for (slot in 0 until inventory.size) {
            val item = inventory.getItem(slot) ?: continue
            update(item, null)?.let { inventory.setItem(slot, it) }
        }
    }

    fun reconcile(player: Player) {
        val element = plugin.items.vision(player)?.damageType
        val inventory = player.inventory
        for (slot in 0 until inventory.size) {
            val item = inventory.getItem(slot) ?: continue
            val desired = element.takeIf { plugin.items.isWeapon(item) && plugin.items.weapon(item)?.damageType == null }
            update(item, desired)?.let { inventory.setItem(slot, it) }
        }
        (player.openInventory.topInventory as Inventory?)?.takeIf { it !== player.inventory }?.let(::clean)
        update(player.itemOnCursor, null)?.let { player.setItemOnCursor(it) }
    }

    fun clear(player: Player) {
        clean(player.inventory)
        (player.openInventory.topInventory as Inventory?)?.takeIf { it !== player.inventory }?.let(::clean)
        update(player.itemOnCursor, null)?.let { player.setItemOnCursor(it) }
    }

    fun particles(player: Player) {
        if (!particlesEnabled(player)) return
        val element = plugin.items.vision(player)?.damageType ?: return
        if (!plugin.items.isWeapon(player.inventory.itemInMainHand) || plugin.items.weapon(player.inventory.itemInMainHand)?.damageType != null) return
        val direction = player.eyeLocation.direction
        val horizontal = direction.clone().setY(0.0)
        if (horizontal.lengthSquared() < 1e-6) horizontal.setX(0.0).setZ(1.0)
        horizontal.normalize()
        val right = org.bukkit.util.Vector(-horizontal.z, 0.0, horizontal.x)
        val side = if (player.mainHand == org.bukkit.inventory.MainHand.LEFT) -0.35 else 0.35
        val location = player.eyeLocation.add(direction.clone().multiply(0.45)).add(right.multiply(side)).subtract(0.0, 0.45, 0.0)
        plugin.particles.burst(element, location, 2)
    }
}
