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
        val weapon = plugin.items.weapon(plugin.items.create("lunar_hydro"))
        assertEquals(Element.HYDRO, weapon?.element)
        assertEquals(1, weapon?.amount)
        assertNotNull(plugin.getCommand("genshin"))
    }

    @Test fun `elemental and physical resistance are independent`() {
        val elemental = plugin.items.weapon(plugin.items.create("lunar_hydro"))!!
        assertEquals(Element.HYDRO, elemental.damageType)
        plugin.config.set("damage.resistance.hydro", 0.25)
        assertEquals(0.75, plugin.damage.resistanceMultiplier(elemental.damageType))
        assertEquals(1.0, plugin.damage.resistanceMultiplier(null))
    }

    @Test fun `configured material and mastery determine lore and glow`() {
        val config = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "items.yml"))
        config.set("items.vision_pyro.material", "BOW")
        config.set("items.vision_pyro.lore", listOf("&aCustom description"))
        config.set("items.vision_pyro.elemental-mastery", 50)
        plugin.items.load(config)
        val item = plugin.items.create("vision_pyro")
        assertEquals(Material.BOW, item.type)
        val lore = item.itemMeta.lore()!!
        val serializer = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
        assertEquals(listOf("Custom description", "Elemental Mastery: 50"), lore.map(serializer::serialize))
        assertFalse(item.itemMeta.hasEnchantmentGlintOverride())
        assertTrue(item.enchantments.isEmpty())
        config.set("items.vision_pyro.elemental-mastery", 51)
        plugin.items.load(config)
        val glowing = plugin.items.create("vision_pyro")
        assertEquals(true, glowing.itemMeta.enchantmentGlintOverride)
        assertTrue(glowing.enchantments.isEmpty())
    }

    @Test fun `give command uses configured material for self and named recipient`() {
        val sender = server.addPlayer().apply { isOp = true }
        val recipient = server.addPlayer()
        assertTrue(server.dispatchCommand(sender, "genshin give vision_pyro"))
        assertEquals(Material.BLAZE_ROD, sender.inventory.contents.filterNotNull().single().type)
        assertTrue(server.dispatchCommand(sender, "genshin give gnosis_pyro ${recipient.name}"))
        val received = recipient.inventory.contents.filterNotNull().single()
        assertEquals(Material.BLAZE_ROD, received.type)
        assertEquals(true, received.itemMeta.enchantmentGlintOverride)
        assertNull(plugin.items.weapon(received))
    }

    @Test fun `registration uses simplified item properties and survives reload`() {
        val item = ItemStack(Material.STICK).apply { editMeta { it.displayName(component("&dTest item")) } }
        val id = plugin.items.register(item)
        val config = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "items.yml"))
        assertFalse(config.contains("items.$id.elements"))
        assertTrue(config.contains("items.$id.lunar.enabled"))
        assertEquals("STICK", config.getString("items.$id.material"))
        assertEquals(Material.STICK, plugin.items.create(id).type)
        config.set("items.$id.item-type", "pyro")
        config.set("items.$id.elemental-mastery", 7)
        config.save(File(plugin.dataFolder, "items.yml"))
        plugin.reloadAll()
        assertEquals(7, plugin.items.weapon(item)?.amount)
        assertEquals(Element.PYRO, plugin.items.weapon(item)?.element)
    }

    @Test fun `item type and property control damage and modifiers`() {
        val config = YamlConfiguration()
        config.set("items.example.name", "Example")
        config.set("items.example.item-type", "pyro")
        config.set("items.example.item-property", "weapon")
        config.set("items.example.damage", 9.0)
        config.set("items.example.elemental-mastery", 42)
        config.set("items.example.stellar.enabled", true)
        config.set("items.example.stellar.bonus", 15)
        plugin.items.load(config)
        val weapon = plugin.items.weapon(plugin.items.create("example"))!!
        assertEquals(Element.PYRO, weapon.element)
        assertEquals(42, weapon.amount)
        assertEquals(9.0, weapon.baseDamage)
        assertEquals(15.0, weapon.stellar)
    }

    @Test fun `invalid YAML leaves the running configuration intact`() {
        val original = plugin.config.getLong("states.pyro")
        File(plugin.dataFolder, "config.yml").writeText("states: [broken")
        assertFails { plugin.reloadAll() }
        assertEquals(original, plugin.config.getLong("states.pyro"))
        assertEquals(100, plugin.items.definitions["gnosis_pyro"]?.mastery)
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
        player.inventory.setItemInMainHand(plugin.items.create("lunar_hydro"))
        val expansion = GenshinExpansion(plugin)
        assertEquals("hydro", expansion.onRequest(player, "element"))
        assertEquals("1", expansion.onRequest(player, "element_amount"))
        assertTrue(expansion.onRequest(player, "element_symbol")!!.isNotBlank())
        assertTrue(expansion.onRequest(player, "element_text")!!.contains("hydro"))
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
        plugin.config.set("holograms.damage.enabled", false)
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
    @Test fun `vision infuses a configured weapon and preserves its base damage`() {
        val target = target()
        val player = server.addPlayer()
        player.teleport(target.location)
        val config = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "items.yml"))
        config.set("items.lunar_hydro.item-type", "physical")
        config.set("items.lunar_hydro.damage", 8.0)
        plugin.items.load(config)
        player.inventory.setItemInMainHand(plugin.items.create("lunar_hydro"))
        player.inventory.setItem(5, plugin.items.create("vision_pyro"))
        plugin.config.set("damage.resistance.pyro", 0.25)
        val hit = EntityDamageByEntityEvent(player, target, DamageCause.ENTITY_ATTACK, 2.0)
        server.pluginManager.callEvent(hit)
        assertEquals(6.0, hit.damage, 1e-9)
        server.scheduler.performTicks(1)
        assertEquals(Element.PYRO, plugin.elements.state(target.uniqueId)?.element)
        assertEquals(1.0, plugin.elements.state(target.uniqueId)?.mastery)
    }

    @Test fun `vision applies to vanilla weapons in inventory order and not to other items`() {
        val player = server.addPlayer()
        player.inventory.setItem(4, plugin.items.create("vision_pyro"))
        player.inventory.setItem(5, plugin.items.create("gnosis_hydro"))
        val sword = ItemStack(Material.IRON_SWORD)
        val infused = plugin.items.attack(player, sword)!!
        assertEquals(Element.PYRO, infused.damageType)
        assertNull(infused.baseDamage)
        assertNull(plugin.items.attack(player, ItemStack(Material.STICK)))
        assertNull(plugin.items.attack(player, plugin.items.create("vision_pyro")))
        player.inventory.setItem(4, null)
        assertEquals(Element.HYDRO, plugin.items.attack(player, sword)?.damageType)
        assertEquals(100, plugin.items.attack(player, sword)?.amount)
    }

    @Suppress("DEPRECATION")
    @Test fun `physical attacks do not apply the enabled element`() {
        val target = target()
        val player = server.addPlayer()
        player.teleport(target.location)
        val config = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "items.yml"))
        config.set("items.lunar_hydro.item-type", "physical")
        plugin.items.load(config)
        player.inventory.setItemInMainHand(plugin.items.create("lunar_hydro"))
        server.pluginManager.callEvent(EntityDamageByEntityEvent(player, target, DamageCause.ENTITY_ATTACK, 1.0))
        server.scheduler.performTicks(1)
        assertNull(plugin.elements.state(target.uniqueId))
    }

    @Suppress("DEPRECATION")
    @Test fun `offhand arrows keep their launch element and bonus after equipment changes`() {
        val target = target()
        val player = server.addPlayer()
        player.teleport(target.location)
        val config = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "items.yml"))
        config.set("items.lunar_hydro.material", "BOW")
        plugin.items.load(config)
        val bow = plugin.items.create("lunar_hydro")
        player.inventory.setItem(5, plugin.items.create("gnosis_pyro"))
        player.inventory.setItemInOffHand(bow)
        plugin.equipment.addTemporary(player, Element.PYRO, 2.0, 100)
        val arrow = target.world.spawnEntity(player.location, EntityType.ARROW) as Arrow
        arrow.shooter = player
        server.pluginManager.callEvent(EntityShootBowEvent(player, bow, ItemStack(Material.ARROW), arrow, EquipmentSlot.OFF_HAND, 1f, true))
        player.inventory.setItemInOffHand(ItemStack(Material.AIR))
        player.inventory.setItem(5, null)
        player.inventory.setItemInMainHand(ItemStack(Material.STICK))
        plugin.equipment.clear(player.uniqueId)
        server.pluginManager.callEvent(EntityDamageByEntityEvent(arrow, target, DamageCause.PROJECTILE, 1.0))
        server.scheduler.performTicks(1)
        assertEquals(Element.PYRO, plugin.elements.state(target.uniqueId)?.element)
        assertEquals(200, plugin.elements.state(target.uniqueId)?.amount)
    }
}
