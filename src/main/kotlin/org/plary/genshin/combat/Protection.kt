package org.plary.genshin.combat

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.plary.genshin.Genshin
import java.util.UUID

class Protection(private val plugin: Genshin) {
    private val excludedTypes = setOf(EntityType.COD, EntityType.SALMON, EntityType.TROPICAL_FISH,
        EntityType.PUFFERFISH, EntityType.SQUID, EntityType.GLOW_SQUID, EntityType.GUARDIAN,
        EntityType.ELDER_GUARDIAN, EntityType.DOLPHIN, EntityType.TURTLE, EntityType.AXOLOTL,
        EntityType.FROG, EntityType.VILLAGER)

    fun npc(entity: Entity): Boolean = entity.hasMetadata("NPC")

    fun eligible(entity: LivingEntity): Boolean = entity.isValid && !entity.isDead && !npc(entity) &&
        entity !is ArmorStand && entity.type !in excludedTypes && !plugin.isTemporary(entity) &&
        (entity !is Player || (entity.isOnline && entity.gameMode != GameMode.CREATIVE && entity.gameMode != GameMode.SPECTATOR))

    fun canAffect(target: LivingEntity, sourceId: UUID?): Boolean {
        if (!eligible(target)) return false
        val source = sourceId?.let(Bukkit::getEntity)
        if (source != null && npc(source)) return false
        if (target is Player) {
            if (!plugin.integrations.pvpAllowed(target.location)) return false
            if (source is Player && source.uniqueId != target.uniqueId) {
                @Suppress("DEPRECATION") val pvp = target.world.pvp
                if (!pvp || source.world != target.world || !plugin.integrations.pvpAllowed(source.location)) return false
                val team = target.scoreboard.getEntryTeam(target.name)
                if (team != null && !team.allowFriendlyFire() && team.hasEntry(source.name)) return false
            }
        }
        return true
    }

    fun nearby(location: Location, radius: Double, sourceId: UUID?, playersOnly: Boolean? = null): List<LivingEntity> {
        val world = location.world ?: return emptyList()
        val r = radius.coerceIn(0.0, 128.0)
        return world.getNearbyLivingEntities(location, r, r, r).filter {
            it.uniqueId != sourceId && (playersOnly == null || (it is Player) == playersOnly) &&
                it.location.distanceSquared(location) <= r * r && canAffect(it, sourceId)
        }
    }
}
