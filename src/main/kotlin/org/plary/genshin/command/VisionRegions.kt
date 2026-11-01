package org.plary.genshin.command

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.plary.genshin.Genshin
import org.plary.genshin.util.component

class VisionRegions(private val plugin: Genshin) : Listener {
    private class Menu(val slot: Int, val original: ItemStack) : InventoryHolder {
        lateinit var menu: Inventory
        override fun getInventory(): Inventory = menu
    }

    companion object {
        private val regions = listOf("mondstadt", "liyue", "inazuma", "sumeru", "fontaine", "natlan", "nod_krai", "snezhnaya")

        fun open(plugin: Genshin, player: Player) {
            val slot = player.inventory.contents.indexOfFirst { plugin.items.regionVisionElement(it) != null }
            require(slot >= 0) { "You need a vision to choose a region." }
            val original = player.inventory.getItem(slot)!!.clone()
            val element = plugin.items.regionVisionElement(original)!!
            val holder = Menu(slot, original)
            holder.menu = Bukkit.createInventory(holder, 9, component("Vision Region"))
            regions.forEachIndexed { index, region ->
                val choice = original.clone()
                choice.editMeta { meta ->
                    val model = meta.customModelDataComponent
                    model.strings = listOf("${element.key}_$region")
                    meta.setCustomModelDataComponent(model)
                    meta.lore(listOf(component("&7${region.replace('_', ' ').replaceFirstChar { it.uppercase() }}")))
                }
                holder.menu.setItem(index, choice)
            }
            player.openInventory(holder.menu)
        }
    }

    @EventHandler
    fun click(event: InventoryClickEvent) {
        val menu = event.view.topInventory.holder as? Menu ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (event.rawSlot !in regions.indices) return
        val current = player.inventory.getItem(menu.slot)
        if (current != menu.original || plugin.items.regionVisionElement(current) == null) {
            player.closeInventory()
            player.sendMessage(component("&cYour vision changed. Open the menu again."))
            return
        }
        val element = plugin.items.regionVisionElement(current)!!
        current!!.editMeta { meta ->
            val model = meta.customModelDataComponent
            model.strings = listOf("${element.key}_${regions[event.rawSlot]}")
            meta.setCustomModelDataComponent(model)
        }
        player.closeInventory()
        player.sendMessage(component("&aVision region changed to ${regions[event.rawSlot].replace('_', ' ')}."))
    }

    @EventHandler
    fun drag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is Menu) event.isCancelled = true
    }
}
