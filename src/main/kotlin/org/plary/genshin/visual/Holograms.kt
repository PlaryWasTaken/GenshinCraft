package org.plary.genshin.visual

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.plary.genshin.Genshin
import org.plary.genshin.element.Element
import org.plary.genshin.element.Reaction
import org.plary.genshin.formula.ReactionInput
import org.plary.genshin.util.component
import org.plary.genshin.util.numeric
import java.util.Locale
import java.util.UUID

private data class FloatingText(val entity: TextDisplay, val target: UUID?, val expiresAt: Long, val created: Long, val offset: Double, val rise: Double)

class Holograms(private val plugin: Genshin) {
    private val states = mutableMapOf<UUID, TextDisplay>()
    private val actionBarStates = mutableSetOf<UUID>()
    private val floating = mutableListOf<FloatingText>()

    private fun visible(location: Location): Boolean {
        val world = location.world ?: return false
        if (!world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) return false
        if (!plugin.config.getBoolean("holograms.optimize.enabled", true)) return true
        val radius = plugin.config.getInt("holograms.optimize.render-chunks", 6).coerceIn(1, 32) * 16.0
        return world.players.any { it.location.distanceSquared(location) <= radius * radius }
    }

    private fun spawn(location: Location, text: String): TextDisplay = location.world!!.spawn(location, TextDisplay::class.java) {
        plugin.markTemporary(it)
        it.billboard = Display.Billboard.CENTER
        it.isShadowed = true
        it.isSeeThrough = false
        it.isDefaultBackground = false
        it.viewRange = (plugin.config.getInt("holograms.optimize.render-chunks", 6).coerceIn(1, 32) / 4.0).toFloat()
        it.teleportDuration = 2
        it.text(component(text))
    }

    fun state(target: LivingEntity) {
        if (target is Player) {
            showPlayerState(target)
            return
        }
        if (!plugin.config.getBoolean("holograms.state.enabled", true) || !visible(target.location)) return
        if (states[target.uniqueId]?.isValid == true) return
        states[target.uniqueId] = spawn(target.location.add(0.0, target.height + 0.8, 0.0), "")
    }

    /** Shows a player's active elemental state without creating an entity at their location. */
    private fun showPlayerState(player: Player) {
        val state = plugin.elements.state(player.uniqueId)
        if (state == null || !plugin.config.getBoolean("action-bars.element-state.enabled", true)) {
            clearPlayerState(player.uniqueId)
            return
        }
        val seconds = String.format(Locale.ROOT, "%.1f", (state.expiresAt - plugin.ticks) / 20.0)
        val text = plugin.config.getString("action-bars.element-state.formats.${state.element.key}",
            plugin.config.getString("action-bars.element-state.format", "{symbol} {element} &7({time}s)"))!!
            .replace("{symbol}", state.element.symbol)
            .replace("{element}", state.element.displayName)
            .replace("{time}", seconds)
        player.sendActionBar(component(text))
        actionBarStates += player.uniqueId
    }

    fun floating(location: Location, text: String, ticks: Long = 40, target: UUID? = null, offset: Double = 0.0, rise: Double = 0.0): TextDisplay? {
        if (!visible(location)) return null
        val display = spawn(location, text)
        floating += FloatingText(display, target, plugin.ticks + ticks.coerceIn(1, 1200), plugin.ticks, offset, rise)
        return display
    }

    private fun reactionKey(reaction: Reaction, input: ReactionInput): String = reaction.key

    fun reaction(target: LivingEntity, reaction: Reaction, input: ReactionInput, follow: Boolean = true) {
        if (!plugin.config.getBoolean("holograms.reaction.enabled", true)) return
        val cfg = plugin.config
        val key = reactionKey(reaction, input)
        val text = cfg.getString("holograms.reaction.formats.$key", cfg.getString("holograms.reaction.format", "&d%reaction%"))!!
            .replace("%reaction%", reaction.label).replace("{key}", reaction.label).replace("{amount}", "")
        val offset = target.height + cfg.numeric("holograms.reaction.y-offset-base", 1.1)
        floating(target.location.add(0.0, offset, 0.0), text, cfg.getLong("holograms.reaction.duration-ticks", 40), target.uniqueId.takeIf { follow }, offset)
    }

    fun damage(target: LivingEntity, reaction: Reaction, input: ReactionInput, amount: Double) {
        if (!plugin.config.getBoolean("holograms.reaction.enabled", true)) return
        val decimals = plugin.config.getInt("holograms.reaction.damage.decimals", 1).coerceIn(0, 6)
        val formatted = String.format(Locale.ROOT, "%.${decimals}f", amount)
        val template = plugin.config.getString("holograms.reaction.damage.format", "&c-{amount}")!!
        val text = template.replace("{amount}", formatted).replace("{key}", reactionKey(reaction, input))
        floating(target.location.add(0.0, target.height + 0.2, 0.0), text, 25, rise = 0.035)
    }

    fun damage(target: LivingEntity, type: Element?, amount: Double) {
        if (!plugin.config.getBoolean("holograms.damage.enabled", true)) return
        val decimals = plugin.config.getInt("holograms.damage.decimals", 1).coerceIn(0, 6)
        val formatted = String.format(Locale.ROOT, "%.${decimals}f", amount)
        val key = type?.key ?: "physical"
        val template = plugin.config.getString("holograms.damage.formats.$key", "&f-{amount}")!!
        floating(target.location.add(0.0, target.height + 0.2, 0.0), template.replace("{amount}", formatted).replace("{type}", key), 25, rise = 0.035)
    }

    fun application(target: LivingEntity, element: Element, amount: Int) {
        val cfg = plugin.config
        if (!cfg.getBoolean("application_holograms.enabled", true)) return
        val text = cfg.getString("application_holograms.formats.${element.key}", cfg.getString("application_holograms.format", "{symbol} {element} x{amount}"))!!
            .replace("{symbol}", element.symbol).replace("{element}", element.displayName).replace("{amount}", "$amount")
        val offset = target.height + cfg.numeric("application_holograms.y-offset", 1.0)
        floating(target.location.add(0.0, offset, 0.0), text, cfg.getLong("application_holograms.duration-ticks", 45), rise = cfg.numeric("application_holograms.up-per-tick", 0.045))
    }

    fun tick() {
        for ((id, display) in states.toMap()) {
            val target = Bukkit.getEntity(id) as? LivingEntity
            val state = plugin.elements.state(id)
            if (target == null || state == null || !display.isValid || !target.isValid || !visible(target.location) || !plugin.config.getBoolean("holograms.state.enabled", true)) {
                clearState(id); continue
            }
            val cfg = plugin.config
            val seconds = String.format(Locale.ROOT, "%.1f", (state.expiresAt - plugin.ticks) / 20.0)
            val text = cfg.getString("holograms.state.formats.${state.element.key}", cfg.getString("holograms.state.format", "%element% (%time%s)"))!!
                .replace("%element%", state.element.displayName).replace("%time%", seconds)
            display.text(component(text))
            val extra = if (target.isCustomNameVisible) cfg.numeric("holograms.y-offset-extra-named", 0.3) else 0.0
            display.teleport(target.location.add(0.0, target.height + cfg.numeric("holograms.state.y-offset-base", 0.8) + extra, 0.0))
        }
        Bukkit.getOnlinePlayers().forEach(::showPlayerState)
        for (text in floating.toList()) {
            val display = text.entity
            val target = text.target?.let(Bukkit::getEntity)
            if (plugin.ticks >= text.expiresAt || !display.isValid || (text.target != null && (target == null || !target.isValid))) {
                display.remove(); floating.remove(text); continue
            }
            if (target != null) display.teleport(target.location.add(0.0, text.offset, 0.0))
            else if (text.rise != 0.0) display.teleport(display.location.add(0.0, text.rise * 2, 0.0))
        }
    }

    fun clearState(id: UUID) {
        states.remove(id)?.remove()
        clearPlayerState(id)
    }

    private fun clearPlayerState(id: UUID) {
        if (actionBarStates.remove(id)) (Bukkit.getEntity(id) as? Player)?.sendActionBar(component(""))
    }
    fun removeNear(center: Location?, radius: Double): Int {
        val displays = states.values + floating.map { it.entity }
        val selected = displays.filter { center == null || (it.world == center.world && it.location.distanceSquared(center) <= radius * radius) }
        selected.forEach { it.remove() }
        states.entries.removeIf { !it.value.isValid }
        floating.removeIf { !it.entity.isValid }
        return selected.size
    }

    fun clear() { removeNear(null, 0.0) }
}
