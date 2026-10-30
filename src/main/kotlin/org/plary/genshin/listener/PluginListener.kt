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
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.event.world.EntitiesUnloadEvent
import org.bukkit.persistence.PersistentDataType
import org.plary.genshin.Genshin
import org.plary.genshin.element.Element
import org.plary.genshin.item.Weapon
import java.util.UUID

class PluginListener(private val plugin: Genshin) : Listener {
    private val elementKey = NamespacedKey(plugin, "projectile-element")
    private val amountKey = NamespacedKey(plugin, "projectile-amount")
    private val lunarKey = NamespacedKey(plugin, "projectile-lunar")
    private val stellarKey = NamespacedKey(plugin, "projectile-stellar")
    private val damageTypeKey = NamespacedKey(plugin, "projectile-damage-type")

    private fun snapshot(source: LivingEntity, weapon: Weapon): Weapon = weapon.copy(amount =
        if (source is Player) plugin.equipment.total(source, weapon.element, weapon.amount, weapon.element == Element.ELEMENTAL) else weapon.amount)

    private fun mark(projectile: Projectile, weapon: Weapon) {
        projectile.persistentDataContainer.apply {
            set(elementKey, PersistentDataType.STRING, weapon.element.key)
            set(amountKey, PersistentDataType.INTEGER, weapon.amount)
            set(lunarKey, PersistentDataType.DOUBLE, weapon.lunar)
            set(stellarKey, PersistentDataType.DOUBLE, weapon.stellar)
            weapon.damageType?.let { set(damageTypeKey, PersistentDataType.STRING, it.key) }
        }
    }

    private fun read(projectile: Projectile): Weapon? {
        val pdc = projectile.persistentDataContainer
        val element = Element.parse(pdc.get(elementKey, PersistentDataType.STRING)) ?: return null
        val amount = pdc.get(amountKey, PersistentDataType.INTEGER) ?: return null
        return Weapon(element, amount, pdc.get(lunarKey, PersistentDataType.DOUBLE) ?: 0.0, pdc.get(stellarKey, PersistentDataType.DOUBLE) ?: 0.0,
            Element.parse(pdc.get(damageTypeKey, PersistentDataType.STRING)))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBow(event: EntityShootBowEvent) {
        val projectile = event.projectile as? Projectile ?: return
        val weapon = plugin.items.weapon(event.bow) ?: return
        mark(projectile, snapshot(event.entity, weapon))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLaunch(event: ProjectileLaunchEvent) {
        val projectile = event.entity
        val shooter = projectile.shooter as? LivingEntity ?: return
        // Arrows are marked using the actual bow in EntityShootBowEvent, including offhand bows.
        if (projectile is AbstractArrow && projectile !is Trident) return
        val item = if (projectile is Trident) projectile.itemStack else shooter.equipment?.itemInMainHand
        val weapon = plugin.items.weapon(item) ?: return
        mark(projectile, snapshot(shooter, weapon))
    }

    private fun fromDamager(damager: Entity): Pair<UUID, Weapon>? = when (damager) {
        is Projectile -> (damager.shooter as? LivingEntity)?.let { source -> read(damager)?.let { source.uniqueId to it } }
        is LivingEntity -> plugin.items.weapon(damager.equipment?.itemInMainHand)?.let { damager.uniqueId to snapshot(damager, it) }
        else -> null
    }

    private fun queue(targetId: UUID, sourceId: UUID, weapon: Weapon) {
        plugin.server.scheduler.runTask(plugin, Runnable {
            val target = Bukkit.getEntity(targetId) as? LivingEntity ?: return@Runnable
            val source = Bukkit.getEntity(sourceId) as? LivingEntity ?: return@Runnable
            if (!source.isValid || source.isDead || !target.isValid || target.isDead || source.world != target.world) return@Runnable
            plugin.elements.apply(target, weapon.element, weapon.amount, sourceId, adjustBonus = false, lunar = weapon.lunar, stellar = weapon.stellar)
        })
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPhysicalDamage(event: EntityDamageByEntityEvent) {
        if (plugin.damage.dealingReactionDamage || event.cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK &&
            event.cause != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK && event.cause != EntityDamageEvent.DamageCause.PROJECTILE) return
        val target = event.entity as? LivingEntity ?: return
        val weapon = fromDamager(event.damager)?.second
        event.damage *= plugin.damage.resistanceMultiplier(weapon?.damageType)
        if (weapon?.damageType == null) event.damage *= maxOf(plugin.reactions.physicalDamageMultiplier(target.uniqueId), plugin.specialReactions.physicalMultiplier(target))
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCoreDamage(event: EntityDamageEvent) {
        if (!plugin.blooms.isCore(event.entity.uniqueId)) return
        if (event is EntityDamageByEntityEvent && !plugin.damage.dealingReactionDamage && event.cause != EntityDamageEvent.DamageCause.THORNS) {
            fromDamager(event.damager)?.let { (source, weapon) -> queue(event.entity.uniqueId, source, weapon) }
        }
        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCombat(event: EntityDamageByEntityEvent) {
        if (plugin.damage.dealingReactionDamage || event.cause == EntityDamageEvent.DamageCause.THORNS || event.finalDamage <= 0) return
        val target = event.entity as? LivingEntity ?: return
        if (!plugin.protection.eligible(target)) return
        fromDamager(event.damager)?.let { (source, weapon) -> queue(target.uniqueId, source, weapon) }
        val victim = target as? Player ?: return
        val attacker = (if (event.damager is Projectile) (event.damager as Projectile).shooter else event.damager) as? LivingEntity ?: return
        val victimId = victim.uniqueId
        val attackerId = attacker.uniqueId
        plugin.server.scheduler.runTask(plugin, Runnable {
            val player = Bukkit.getPlayer(victimId) ?: return@Runnable
            val enemy = Bukkit.getEntity(attackerId) as? LivingEntity ?: return@Runnable
            if (player.isDead || !enemy.isValid || enemy.isDead || player.world != enemy.world) return@Runnable
            plugin.equipment.counterattack(player, enemy)
        })
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
                EntityDamageEvent.DamageCause.FIRE, EntityDamageEvent.DamageCause.FIRE_TICK, EntityDamageEvent.DamageCause.LAVA) && !cactus) return
        plugin.server.scheduler.runTask(plugin, Runnable {
            val target = Bukkit.getEntity(id) as? LivingEntity ?: return@Runnable
            when (cause) {
                EntityDamageEvent.DamageCause.LIGHTNING -> plugin.ambient.apply(target, Element.ELECTRO, "lightning")
                EntityDamageEvent.DamageCause.FALL -> plugin.ambient.fall(target)
                EntityDamageEvent.DamageCause.CONTACT -> if (cactus) plugin.ambient.apply(target, Element.DENDRO, "cactus")
                EntityDamageEvent.DamageCause.FIRE, EntityDamageEvent.DamageCause.FIRE_TICK, EntityDamageEvent.DamageCause.LAVA -> plugin.ambient.scan(target)
                else -> Unit
            }
        })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val to = event.to
        if (event.from.blockX != to.blockX || event.from.blockY != to.blockY || event.from.blockZ != to.blockZ) plugin.ambient.scan(event.player)
    }

    @EventHandler fun onDeath(event: EntityDeathEvent) { plugin.clearEntity(event.entity.uniqueId) }
    @EventHandler fun onQuit(event: PlayerQuitEvent) { plugin.clearEntity(event.player.uniqueId); plugin.regions.forget(event.player.uniqueId) }
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
