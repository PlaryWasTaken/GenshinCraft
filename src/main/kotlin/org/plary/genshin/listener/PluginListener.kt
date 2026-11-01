package org.plary.genshin.listener

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.*
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.PrepareItemCraftEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.PrepareSmithingEvent
import org.bukkit.event.inventory.SmithItemEvent
import org.bukkit.event.block.CrafterCraftEvent
import org.bukkit.block.Crafter
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.event.world.EntitiesUnloadEvent
import org.bukkit.persistence.PersistentDataType
import org.plary.genshin.Genshin
import org.plary.genshin.element.Element
import org.plary.genshin.item.Weapon
import java.util.UUID

class PluginListener(private val plugin: Genshin) : Listener {
    @EventHandler(priority = EventPriority.HIGH)
    fun onVisionUse(event: org.bukkit.event.player.PlayerInteractEvent) {
        if (event.action != org.bukkit.event.block.Action.RIGHT_CLICK_AIR && event.action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return
        if (event.item?.type == org.bukkit.Material.ENDER_EYE && plugin.items.regionVisionElement(event.item) != null) {
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY)
        }
    }

    private val elementKey = NamespacedKey(plugin, "projectile-element")
    private val amountKey = NamespacedKey(plugin, "projectile-amount")
    private val lunarKey = NamespacedKey(plugin, "projectile-lunar")
    private val stellarKey = NamespacedKey(plugin, "projectile-stellar")
    private val damageBonusKey = NamespacedKey(plugin, "projectile-elemental-damage-percent")
    private val baseDamageKey = NamespacedKey(plugin, "projectile-base-damage")
    private val damageTypeKey = NamespacedKey(plugin, "projectile-damage-type")

    private fun snapshot(source: LivingEntity, weapon: Weapon): Weapon = weapon.copy(amount =
        if (source is Player) plugin.equipment.total(source, weapon.damageType ?: weapon.element, weapon.amount, weapon.element == Element.ELEMENTAL) else weapon.amount)

    private fun mark(projectile: Projectile, weapon: Weapon) {
        projectile.persistentDataContainer.apply {
            set(elementKey, PersistentDataType.STRING, weapon.element.key)
            set(amountKey, PersistentDataType.INTEGER, weapon.amount)
            set(damageBonusKey, PersistentDataType.DOUBLE, weapon.elementalDamagePercent)
            set(lunarKey, PersistentDataType.DOUBLE, weapon.lunar)
            set(stellarKey, PersistentDataType.DOUBLE, weapon.stellar)
            weapon.damageType?.let { set(damageTypeKey, PersistentDataType.STRING, it.key) }
            weapon.baseDamage?.let { set(baseDamageKey, PersistentDataType.DOUBLE, it) }
        }
    }

    private fun read(projectile: Projectile): Weapon? {
        val pdc = projectile.persistentDataContainer
        val element = Element.parse(pdc.get(elementKey, PersistentDataType.STRING)) ?: return null
        val amount = pdc.get(amountKey, PersistentDataType.INTEGER) ?: return null
        return Weapon(element, amount, pdc.get(lunarKey, PersistentDataType.DOUBLE) ?: 0.0, pdc.get(stellarKey, PersistentDataType.DOUBLE) ?: 0.0,
            Element.parse(pdc.get(damageTypeKey, PersistentDataType.STRING)), pdc.get(baseDamageKey, PersistentDataType.DOUBLE), pdc.get(damageBonusKey, PersistentDataType.DOUBLE) ?: 0.0)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBow(event: EntityShootBowEvent) {
        val projectile = event.projectile as? Projectile ?: return
        val weapon = plugin.items.attack(event.entity, event.bow) ?: return
        mark(projectile, snapshot(event.entity, weapon))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLaunch(event: ProjectileLaunchEvent) {
        val projectile = event.entity
        val shooter = projectile.shooter as? LivingEntity ?: return
        // Arrows are marked using the actual bow in EntityShootBowEvent, including offhand bows.
        if (projectile !is Trident) return
        val weapon = plugin.items.attack(shooter, projectile.itemStack) ?: return
        mark(projectile, snapshot(shooter, weapon))
    }

    private fun fromDamager(damager: Entity): Pair<UUID, Weapon>? = when (damager) {
        is Projectile -> (damager.shooter as? LivingEntity)?.let { source -> read(damager)?.let { source.uniqueId to it } }
        is LivingEntity -> plugin.items.attack(damager, damager.equipment?.itemInMainHand)?.let { damager.uniqueId to snapshot(damager, it) }
        else -> null
    }

    private fun queue(targetId: UUID, sourceId: UUID, weapon: Weapon, attackDamage: Double = 0.0) {
        plugin.server.scheduler.runTask(plugin, Runnable {
            val target = Bukkit.getEntity(targetId) as? LivingEntity ?: return@Runnable
            val source = Bukkit.getEntity(sourceId) as? LivingEntity ?: return@Runnable
            if (!source.isValid || source.isDead || !target.isValid || target.isDead || source.world != target.world) return@Runnable
            plugin.elements.apply(target, weapon.element, maxOf(1, weapon.amount), sourceId, adjustBonus = false, lunar = weapon.lunar, stellar = weapon.stellar, attackDamage = attackDamage, mastery = weapon.amount.toDouble())
        })
    }

    private fun damageType(event: EntityDamageEvent): Element? {
        if (event is EntityDamageByEntityEvent && event.cause in setOf(EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK, EntityDamageEvent.DamageCause.PROJECTILE)) {
            fromDamager(event.damager)?.second?.damageType?.let { return it }
            if (event.damager is Fireball) return Element.PYRO
        }
        return when (event.cause) {
            EntityDamageEvent.DamageCause.FIRE, EntityDamageEvent.DamageCause.FIRE_TICK,
            EntityDamageEvent.DamageCause.LAVA, EntityDamageEvent.DamageCause.HOT_FLOOR -> Element.PYRO
            EntityDamageEvent.DamageCause.LIGHTNING -> Element.ELECTRO
            EntityDamageEvent.DamageCause.FREEZE -> Element.CRYO
            EntityDamageEvent.DamageCause.DROWNING -> Element.HYDRO
            else -> null
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPhysicalDamage(event: EntityDamageEvent) {
        if (plugin.damage.dealingReactionDamage) return
        val target = event.entity as? LivingEntity ?: return
        val type = damageType(event)
        if (event is EntityDamageByEntityEvent && event.cause in setOf(EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK, EntityDamageEvent.DamageCause.PROJECTILE)) {
            val weapon = fromDamager(event.damager)?.second
            weapon?.baseDamage?.let { event.damage = it }
            if (type != null && weapon != null) event.damage *= 1.0 + weapon.elementalDamagePercent / 100.0
        }
        event.damage *= plugin.damage.resistanceMultiplier(type)
        if (type == null) event.damage *= maxOf(plugin.reactions.physicalDamageMultiplier(target.uniqueId), plugin.specialReactions.physicalMultiplier(target))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamageDisplay(event: EntityDamageEvent) {
        if (plugin.damage.dealingReactionDamage || event.finalDamage <= 0) return
        val target = event.entity as? LivingEntity ?: return
        plugin.holograms.damage(target, damageType(event), event.finalDamage)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCoreDamage(event: EntityDamageEvent) {
        if (!plugin.blooms.isCore(event.entity.uniqueId)) return
        if (event is EntityDamageByEntityEvent && !plugin.damage.dealingReactionDamage && event.cause != EntityDamageEvent.DamageCause.THORNS) {
            fromDamager(event.damager)?.let { (source, weapon) -> if (weapon.damageType != null) queue(event.entity.uniqueId, source, weapon.copy(element = weapon.damageType)) }
        }
        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCombat(event: EntityDamageByEntityEvent) {
        if (plugin.damage.dealingReactionDamage || event.cause == EntityDamageEvent.DamageCause.THORNS || event.finalDamage <= 0) return
        val target = event.entity as? LivingEntity ?: return
        if (!plugin.protection.eligible(target)) return
        val type = damageType(event) ?: return
        val marked = fromDamager(event.damager)
        val source = marked?.first ?: ((event.damager as? Projectile)?.shooter as? LivingEntity)?.uniqueId ?: return
        val weapon = marked?.second ?: Weapon(type, 0, damageType = type)
        queue(target.uniqueId, source, weapon.copy(element = type), event.finalDamage)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onAmbientDamage(event: EntityDamageEvent) {
        if (plugin.damage.dealingReactionDamage || event.finalDamage <= 0) return
        val entity = event.entity as? LivingEntity ?: return
        if (!plugin.protection.eligible(entity)) return
        val id = entity.uniqueId
        val cause = event.cause
        val cactus = event is EntityDamageByBlockEvent && event.damager?.type == org.bukkit.Material.CACTUS
        if (cause !in setOf(EntityDamageEvent.DamageCause.LIGHTNING, EntityDamageEvent.DamageCause.FALL,
                EntityDamageEvent.DamageCause.FIRE, EntityDamageEvent.DamageCause.FIRE_TICK, EntityDamageEvent.DamageCause.LAVA,
                EntityDamageEvent.DamageCause.HOT_FLOOR, EntityDamageEvent.DamageCause.FREEZE, EntityDamageEvent.DamageCause.DROWNING) && !cactus) return
        plugin.server.scheduler.runTask(plugin, Runnable {
            val target = Bukkit.getEntity(id) as? LivingEntity ?: return@Runnable
            when (cause) {
                EntityDamageEvent.DamageCause.LIGHTNING -> plugin.ambient.apply(target, Element.ELECTRO, "lightning")
                EntityDamageEvent.DamageCause.FALL -> plugin.ambient.fall(target)
                EntityDamageEvent.DamageCause.CONTACT -> if (cactus) plugin.ambient.apply(target, Element.DENDRO, "cactus")
                EntityDamageEvent.DamageCause.FIRE, EntityDamageEvent.DamageCause.FIRE_TICK, EntityDamageEvent.DamageCause.LAVA,
                EntityDamageEvent.DamageCause.HOT_FLOOR -> plugin.ambient.apply(target, Element.PYRO, "fire")
                EntityDamageEvent.DamageCause.FREEZE -> plugin.ambient.apply(target, Element.CRYO, "powder-snow")
                EntityDamageEvent.DamageCause.DROWNING -> plugin.ambient.apply(target, Element.HYDRO, "water")
                else -> Unit
            }
        })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val to = event.to
        if (event.from.blockX != to.blockX || event.from.blockY != to.blockY || event.from.blockZ != to.blockZ) plugin.ambient.scan(event.player)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onPrepareCraft(event: PrepareItemCraftEvent) {
        if (event.inventory.matrix.any(plugin.items::isPluginItem) || plugin.items.isPluginItem(event.inventory.result))
            event.inventory.result = null
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCraft(event: CraftItemEvent) {
        if (event.inventory.matrix.any(plugin.items::isPluginItem) || plugin.items.isPluginItem(event.currentItem)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onPrepareSmithing(event: PrepareSmithingEvent) {
        val inventory = event.inventory
        if (listOf(inventory.inputTemplate, inventory.inputEquipment, inventory.inputMineral, event.result).any(plugin.items::isPluginItem)) event.result = null
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSmith(event: SmithItemEvent) {
        val inventory = event.inventory
        if (listOf(inventory.inputTemplate, inventory.inputEquipment, inventory.inputMineral, event.currentItem).any(plugin.items::isPluginItem)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCrafter(event: CrafterCraftEvent) {
        val crafter = event.block.state as? Crafter ?: return
        if (crafter.inventory.contents.any(plugin.items::isPluginItem) || plugin.items.isPluginItem(event.result)) event.isCancelled = true
    }

    @EventHandler fun onDeath(event: EntityDeathEvent) {
        if (event.entity is Player) {
            for (index in event.drops.indices) plugin.infusionVisuals.update(event.drops[index], null)?.let { event.drops[index] = it }
        }
        plugin.clearEntity(event.entity.uniqueId)
    }
    @EventHandler(ignoreCancelled = true) fun onDrop(event: PlayerDropItemEvent) {
        val dropped = event.itemDrop
        plugin.infusionVisuals.update(dropped.itemStack, null)?.let { dropped.itemStack = it }
    }
    @EventHandler fun onInventoryClose(event: InventoryCloseEvent) {
        if (event.inventory !== event.player.inventory) plugin.infusionVisuals.clean(event.inventory)
    }
    @EventHandler fun onQuit(event: PlayerQuitEvent) { plugin.infusionVisuals.clear(event.player); plugin.clearEntity(event.player.uniqueId); plugin.regions.forget(event.player.uniqueId) }
    @EventHandler fun onWorldChange(event: PlayerChangedWorldEvent) { plugin.clearEntity(event.player.uniqueId) }

    @EventHandler fun onUnload(event: EntitiesUnloadEvent) {
        event.entities.forEach {
            plugin.clearEntity(it.uniqueId)
            if (plugin.isTemporary(it)) it.remove()
        }
    }

    @EventHandler fun onLoad(event: EntitiesLoadEvent) { event.entities.filter(plugin::isTemporary).forEach { it.remove() } }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTarget(event: EntityTargetLivingEntityEvent) {
        if (plugin.isTemporary(event.entity) || event.target?.let(plugin::isTemporary) == true) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCombust(event: EntityCombustEvent) { if (plugin.isTemporary(event.entity)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTransform(event: EntityTransformEvent) { if (plugin.isTemporary(event.entity)) event.isCancelled = true }
}
