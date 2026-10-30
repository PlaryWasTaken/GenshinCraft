package org.plary.genshin

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.bukkit.Location
import org.bukkit.entity.Cow
import org.bukkit.entity.EntityType
import org.bukkit.entity.Arrow
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.inventory.EquipmentSlot
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.plary.genshin.element.Element
import org.plary.genshin.equipment.EquipmentEffect
import org.plary.genshin.integration.GenshinExpansion
import org.plary.genshin.util.component
import java.io.File
import kotlin.test.*

class PluginTest {
    private lateinit var server: ServerMock
    private lateinit var plugin: Genshin

    @BeforeTest fun setup() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(Genshin::class.java)
    }

    @AfterTest fun teardown() { MockBukkit.unmock() }

    @Test fun `starts without any optional plugins and loads configured weapons`() {
        assertTrue(plugin.isEnabled)
        assertTrue(plugin.items.definitions.isNotEmpty())
        val weapon = plugin.items.weapon(plugin.items.create("example_fire_sword", "sword"))
        assertEquals(Element.PYRO, weapon?.element)
        assertEquals(10, weapon?.amount)
        assertNotNull(plugin.getCommand("genshin"))
    }

    @Test fun `damage type is independent of elemental application`() {
        val elemental = plugin.items.weapon(plugin.items.create("example_sacred_lunar_fire_sword", "sword"))!!
        val physical = plugin.items.weapon(plugin.items.create("example_fire_sword", "sword"))!!
        assertEquals(Element.PYRO, elemental.damageType)
        assertNull(physical.damageType)
        plugin.config.set("damage.resistance.pyro", 0.25)
        assertEquals(0.75, plugin.damage.resistanceMultiplier(elemental.damageType))
        assertEquals(1.0, plugin.damage.resistanceMultiplier(physical.damageType))
    }

    @Test fun `registration produces real element paths and survives reload`() {
        val item = ItemStack(Material.STICK).apply { editMeta { it.displayName(component("&dTest item")) } }
        val id = plugin.items.register(item)
        val config = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "items.yml"))
        assertTrue(config.contains("items.$id.elements.pyro.enabled"))
        config.set("items.$id.elements.pyro.enabled", true)
        config.set("items.$id.elements.pyro.amount", 7)
        config.save(File(plugin.dataFolder, "items.yml"))
        plugin.reloadAll()
        assertEquals(7, plugin.items.weapon(item)?.amount)
        assertEquals(Element.PYRO, plugin.items.weapon(item)?.element)
    }

    @Test fun `invalid YAML leaves the running configuration intact`() {
        val original = plugin.config.getLong("states.pyro")
        File(plugin.dataFolder, "config.yml").writeText("states: [broken")
        assertFails { plugin.reloadAll() }
        assertEquals(original, plugin.config.getLong("states.pyro"))
        assertEquals(10, plugin.items.definitions["example_fire_sword"]?.weapon?.amount)
    }

    @Test fun `slot effects follow the configured slot and live equipment`() {
        val player = server.addPlayer()
        val rune = ItemStack(Material.PAPER).apply { editMeta { it.lore(listOf(component("&cFireproof"))) } }
        player.inventory.setItem(11, rune)
        assertTrue(plugin.equipment.active(player, EquipmentEffect.FIREPROOF))
        player.inventory.setItem(11, null)
        player.inventory.setItem(10, rune)
        assertFalse(plugin.equipment.active(player, EquipmentEffect.FIREPROOF))
    }

    @Test fun `offhand artifacts are counted once and disabled artifacts give no bonus`() {
        val player = server.addPlayer()
        plugin.config.set("artifact.use-inventory-slots", false)
        val rune = ItemStack(Material.PAPER).apply { editMeta { it.lore(listOf(component("&6❖&fElemental application increase &6&lElemental&8: &a6"))) } }
        player.inventory.setItemInOffHand(rune)
        assertEquals(16, plugin.equipment.total(player, Element.PYRO, 10))
        plugin.config.set("effects.artifact", false)
        assertEquals(10, plugin.equipment.total(player, Element.PYRO, 10))
    }

    @Test fun `exact placeholders remain reachable before prefix handlers`() {
        val player = server.addPlayer()
        player.inventory.setItemInMainHand(plugin.items.create("example_fire_sword", "sword"))
        val expansion = GenshinExpansion(plugin)
        assertEquals("pyro", expansion.onRequest(player, "element"))
        assertEquals("10", expansion.onRequest(player, "element_amount"))
        assertTrue(expansion.onRequest(player, "element_symbol")!!.contains("🔥"))
        assertTrue(expansion.onRequest(player, "element_text")!!.contains("pyro"))
        assertEquals("1.5", expansion.onRequest(null, "reaction_mult_vaporize"))
    }

    @Test fun `bonus command enforces permissions and rejects nonfinite multipliers`() {
        val player = server.addPlayer()
        assertTrue(server.dispatchCommand(player, "genshin bonuselement pyro 2 player ${player.name} 10s"))
        assertFalse(plugin.equipment.hasTemporary(player))
        player.isOp = true
        server.dispatchCommand(player, "genshin bonuselement pyro NaN player ${player.name} 10s")
        assertFalse(plugin.equipment.hasTemporary(player))
        server.dispatchCommand(player, "genshin bonuselement pyro 2 player ${player.name} 10s")
        assertTrue(plugin.equipment.hasTemporary(player))
        assertEquals(20, plugin.equipment.total(player, Element.PYRO, 10))
    }

    @Test fun `temporary bonuses expire in ticks and cleanup on quit`() {
        val player = server.addPlayer()
        plugin.equipment.addTemporary(player, Element.PYRO, 2.0, 2)
        assertNotNull(plugin.equipment.temporary(player, Element.PYRO))
        server.scheduler.performTicks(2)
        assertNull(plugin.equipment.temporary(player, Element.PYRO))
        plugin.equipment.addTemporary(player, Element.PYRO, 2.0, 200)
        plugin.clearEntity(player.uniqueId)
        assertFalse(plugin.equipment.hasTemporary(player))
    }

    private fun target(): Cow {
        plugin.config.set("holograms.state.enabled", false)
        plugin.config.set("holograms.reaction.enabled", false)
        plugin.config.set("application_holograms.enabled", false)
        val world = server.addSimpleWorld("combat")
        return world.spawnEntity(Location(world, 0.0, 80.0, 0.0), EntityType.COW) as Cow
    }

    @Test fun `blocked reactions preserve the existing element until cooldown expires`() {
        val target = target()
        plugin.config.set("reactions.multipliers.vaporize", 0)
        assertTrue(plugin.elements.apply(target, Element.HYDRO, 1))
        assertTrue(plugin.elements.apply(target, Element.PYRO, 1))
        assertNull(plugin.elements.state(target.uniqueId))
        assertTrue(plugin.elements.apply(target, Element.DENDRO, 5))
        assertFalse(plugin.elements.apply(target, Element.ELECTRO, 2))
        assertEquals(Element.DENDRO, plugin.elements.state(target.uniqueId)?.element)
        assertEquals(5, plugin.elements.state(target.uniqueId)?.amount)
        server.scheduler.performTicks(65)
        assertTrue(plugin.elements.apply(target, Element.ELECTRO, 2))
        assertNull(plugin.elements.state(target.uniqueId))
    }

    @Test fun `expired states disappear and cannot react`() {
        val target = target()
        plugin.elements.apply(target, Element.HYDRO, 5, duration = 2)
        server.scheduler.performTicks(2)
        assertNull(plugin.elements.state(target.uniqueId))
        assertTrue(plugin.elements.apply(target, Element.PYRO, 5))
        assertEquals(Element.PYRO, plugin.elements.state(target.uniqueId)?.element)
    }

    @Suppress("DEPRECATION")
    @Test fun `offhand arrows keep their launch element and bonus after equipment changes`() {
        val target = target()
        val player = server.addPlayer()
        player.teleport(target.location)
        val bow = plugin.items.create("example_fire_sword", "bow")
        player.inventory.setItemInOffHand(bow)
        plugin.equipment.addTemporary(player, Element.PYRO, 2.0, 100)
        val arrow = target.world.spawnEntity(player.location, EntityType.ARROW) as Arrow
        arrow.shooter = player
        server.pluginManager.callEvent(EntityShootBowEvent(player, bow, ItemStack(Material.ARROW), arrow, EquipmentSlot.OFF_HAND, 1f, true))
        player.inventory.setItemInOffHand(ItemStack(Material.AIR))
        player.inventory.setItemInMainHand(ItemStack(Material.STICK))
        plugin.equipment.clear(player.uniqueId)
        server.pluginManager.callEvent(EntityDamageByEntityEvent(arrow, target, DamageCause.PROJECTILE, 1.0))
        server.scheduler.performTicks(1)
        assertEquals(Element.PYRO, plugin.elements.state(target.uniqueId)?.element)
        assertEquals(20, plugin.elements.state(target.uniqueId)?.amount)
    }
}
