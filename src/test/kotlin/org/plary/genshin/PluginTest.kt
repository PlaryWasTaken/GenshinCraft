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

    @Test fun `plugin items are identified by persistent id and limited to one per stack`() {
        val first = plugin.items.create("vision_pyro")
        val second = plugin.items.create("vision_pyro")
        assertTrue(plugin.items.isPluginItem(first))
        assertTrue(plugin.items.isPluginItem(second))
        assertEquals(1, first.maxStackSize)
        assertEquals(1, second.maxStackSize)
        val ordinary = ItemStack(first.type).apply { editMeta { it.displayName(first.itemMeta.displayName()) } }
        assertFalse(plugin.items.isPluginItem(ordinary))
        assertFalse(plugin.items.isPluginItem(null))
    }

    @Test fun `vision lore displays damage bonus and celestial modifier`() {
        val config = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "items.yml"))
        config.set("items.vision_pyro.elemental-damage-percent", 25.5)
        config.set("items.vision_pyro.lunar.enabled", true)
        config.set("items.vision_pyro.lunar.bonus", 10)
        plugin.items.load(config)
        val serializer = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
        fun lore() = plugin.items.create("vision_pyro").itemMeta.lore()!!.map(serializer::serialize)
        assertTrue("Elemental Damage: +25.5%" in lore())
        assertTrue("Moonlit" in lore())
        config.set("items.vision_pyro.lunar.enabled", false)
        config.set("items.vision_pyro.stellar.enabled", true)
        config.set("items.vision_pyro.stellar.bonus", 10)
        plugin.items.load(config)
        assertTrue("Stellar" in lore())
        assertFalse("Moonlit" in lore())
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

    @Test fun `weapon particle preference is personal persistent and toggleable`() {
        val player = server.addPlayer()
        val other = server.addPlayer()
        assertTrue(plugin.infusionVisuals.particlesEnabled(player))
        server.dispatchCommand(player, "genshin weaponeffect off")
        assertFalse(plugin.infusionVisuals.particlesEnabled(player))
        assertTrue(plugin.infusionVisuals.particlesEnabled(other))
        assertFalse(org.plary.genshin.visual.InfusionVisuals(plugin).particlesEnabled(player))
        server.dispatchCommand(player, "genshin weaponeffect invalid")
        assertFalse(plugin.infusionVisuals.particlesEnabled(player))
        server.dispatchCommand(player, "genshin weaponeffect")
        assertTrue(plugin.infusionVisuals.particlesEnabled(player))
        server.dispatchCommand(player, "genshin weaponeffect off")
        server.dispatchCommand(player, "genshin weaponeffect on")
        assertTrue(plugin.infusionVisuals.particlesEnabled(player))
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

    @Test fun `ambient refresh preserves celestial status and original mastery`() {
        val target = target()
        val source = server.addPlayer()
        plugin.config.set("elements.apply-cooldown-ticks", 0)
        plugin.config.set("states.hydro", 185)
        plugin.config.set("states.lunar", 20)
        assertTrue(plugin.elements.apply(target, Element.HYDRO, 7, source.uniqueId, lunar = 20.0, mastery = 7.0))
        server.scheduler.performTicks(30)
        assertTrue(plugin.elements.apply(target, Element.HYDRO, 2, ambient = true))
        val state = plugin.elements.state(target.uniqueId)!!
        assertEquals(source.uniqueId, state.sourceId)
        assertEquals(7.0, state.mastery)
        val modifier = plugin.elements.modifier(target.uniqueId)!!
        assertEquals(org.plary.genshin.element.Reaction.LUNAR_CRYSTALLIZE,
            org.plary.genshin.element.Reaction.determineSpecial(state.element, Element.GEO, modifier.lunar, modifier.stellar))
    }

    @Suppress("DEPRECATION")
    @Test fun `vision infuses a configured weapon and preserves its base damage`() {
        val target = target()
        val player = server.addPlayer()
        player.teleport(target.location)
        val config = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "items.yml"))
        config.set("items.lunar_hydro.item-type", "physical")
        config.set("items.lunar_hydro.damage", 8.0)
        config.set("items.lunar_hydro.elemental-damage-percent", 20)
        config.set("items.vision_pyro.elemental-damage-percent", 30)
        plugin.items.load(config)
        player.inventory.setItemInMainHand(plugin.items.create("lunar_hydro"))
        player.inventory.setItem(5, plugin.items.create("vision_pyro"))
        plugin.config.set("damage.resistance.pyro", 0.25)
        val hit = EntityDamageByEntityEvent(player, target, DamageCause.ENTITY_ATTACK, 2.0)
        server.pluginManager.callEvent(hit)
        assertEquals(9.0, hit.damage, 1e-9)
        server.scheduler.performTicks(1)
        assertEquals(Element.PYRO, plugin.elements.state(target.uniqueId)?.element)
        assertEquals(1.0, plugin.elements.state(target.uniqueId)?.mastery)
    }

    @Test fun `elemental weapon takes priority over vision`() {
        val player = server.addPlayer()
        player.inventory.setItem(4, plugin.items.create("gnosis_hydro"))
        val config = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "items.yml"))
        config.set("items.lunar_hydro.item-type", "pyro")
        config.set("items.lunar_hydro.elemental-mastery", 7)
        plugin.items.load(config)
        val sword = plugin.items.create("lunar_hydro")
        player.inventory.setItemInMainHand(sword)
        val attack = plugin.items.attack(player, sword)!!
        assertEquals(Element.PYRO, attack.damageType)
        assertEquals(7, attack.amount)
        assertEquals(20.0, attack.lunar)
        plugin.infusionVisuals.reconcile(player)
        val lore = player.inventory.itemInMainHand.itemMeta.lore().orEmpty()
        val serializer = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
        assertFalse(lore.any { serializer.serialize(it).startsWith("Infused with") })
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

    @Test fun `infusion lore follows vision changes without damaging existing item lore`() {
        val player = server.addPlayer()
        val sword = ItemStack(Material.IRON_SWORD).apply {
            editMeta { it.lore(listOf(component("&aOriginal lore"))) }
        }
        val original = sword.clone()
        player.inventory.setItem(0, sword)
        val config = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "items.yml"))
        config.set("items.lunar_hydro.item-type", "physical")
        plugin.items.load(config)
        player.inventory.setItem(1, plugin.items.create("lunar_hydro"))
        player.inventory.setItem(5, plugin.items.create("vision_electro"))
        val serializer = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
        fun lore(slot: Int) = player.inventory.getItem(slot)!!.itemMeta.lore().orEmpty().map(serializer::serialize)

        plugin.infusionVisuals.reconcile(player)
        assertEquals(listOf("Original lore", "Infused with Electro"), lore(0))
        assertEquals(net.kyori.adventure.text.format.NamedTextColor.DARK_PURPLE,
            player.inventory.getItem(0)!!.itemMeta.lore()!!.last().color())
        assertEquals("Infused with Electro", lore(1).last())
        assertFalse(lore(5).any { it.startsWith("Infused with") })
        plugin.infusionVisuals.reconcile(player)
        assertEquals(2, lore(0).size)

        player.inventory.setItem(5, plugin.items.create("vision_hydro"))
        plugin.infusionVisuals.reconcile(player)
        assertEquals(listOf("Original lore", "Infused with Hydro"), lore(0))
        player.inventory.setItem(5, null)
        plugin.infusionVisuals.reconcile(player)
        assertEquals(original, player.inventory.getItem(0))
        assertFalse(lore(1).any { it.startsWith("Infused with") })
    }

    @Test fun `infusion cleanup preserves an identical existing lore line`() {
        val original = ItemStack(Material.DIAMOND_SWORD).apply {
            editMeta { it.lore(listOf(net.kyori.adventure.text.Component.text("Infused with Electro",
                net.kyori.adventure.text.format.NamedTextColor.DARK_PURPLE)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))) }
        }
        val infused = plugin.infusionVisuals.update(original, Element.ELECTRO)!!
        assertEquals(2, infused.itemMeta.lore()!!.size)
        val restored = plugin.infusionVisuals.update(infused, null)!!
        assertEquals(original, restored)
        assertNull(plugin.infusionVisuals.update(restored, null))
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
        config.set("items.lunar_hydro.item-type", "physical")
        config.set("items.gnosis_pyro.elemental-damage-percent", 50)
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
        val hit = EntityDamageByEntityEvent(arrow, target, DamageCause.PROJECTILE, 1.0)
        server.pluginManager.callEvent(hit)
        assertEquals(9.0, hit.damage, 1e-9)
        server.scheduler.performTicks(1)
        assertEquals(Element.PYRO, plugin.elements.state(target.uniqueId)?.element)
        assertEquals(200, plugin.elements.state(target.uniqueId)?.amount)
    }
}
