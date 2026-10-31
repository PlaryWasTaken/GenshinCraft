package org.plary.genshin.command

import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.plary.genshin.Genshin
import org.plary.genshin.element.Element
import org.plary.genshin.equipment.EquipmentEffect
import org.plary.genshin.util.component
import org.plary.genshin.util.durationTicks
import java.util.Locale
import java.util.logging.Level

class GenshinCommand(private val plugin: Genshin) : CommandExecutor, TabCompleter {
    private val permissions = linkedMapOf("reload" to "reload", "register" to "register", "element" to "element",
        "putelement" to "putelement", "give" to "give", "removeholo" to "removeholo", "armorstatus" to "armorstatus",
        "artifactstatus" to "artifactstatus", "pos1" to "regions", "pos2" to "regions", "createregion" to "regions", "bonuselement" to "bonuselement")

    private fun allowed(sender: CommandSender, sub: String): Boolean = permissions[sub]?.let {
        sender.hasPermission("genshin.$it")
    } == true

    private fun player(sender: CommandSender): Player = sender as? Player ?: throw IllegalArgumentException("This command requires a player.")
    private fun online(name: String): Player = requireNotNull(Bukkit.getPlayerExact(name)) { "Player '$name' is not online." }
    private fun element(text: String): Element = requireNotNull(Element.parse(text)) { "Unknown element '$text'." }
    private fun amount(text: String): Int = requireNotNull(text.toIntOrNull()?.takeIf { it > 0 }) { "Amount must be a positive integer." }
    private fun radius(text: String): Double = requireNotNull(text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.5..128.0 }) { "Distance/radius must be between 0.5 and 128 blocks." }
    private fun reply(sender: CommandSender, text: String) { sender.sendMessage(component(text)) }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val sub = args.firstOrNull()?.lowercase(Locale.ROOT)
        if (sub == null) {
            reply(sender, "&6/$label &f<${permissions.keys.filter { allowed(sender, it) }.joinToString("|")}>")
            return true
        }
        if (!allowed(sender, sub)) {
            reply(sender, if (sub in permissions) "&cYou do not have permission to use this command." else "&cUnknown subcommand. Use /$label for help.")
            return true
        }
        try {
            when (sub) {
                "reload" -> { plugin.reloadAll(); reply(sender, "&aGenshin configuration reloaded.") }
                "register" -> {
                    val id = plugin.items.register(player(sender).inventory.itemInMainHand)
                    reply(sender, "&aRegistered as &f$id&a. Set its item type, property, damage and mastery in items.yml, then run /$label reload.")
                }
                "element" -> {
                    require(args.size == 5) { "Usage: /$label element <player> <element> <amount> <time: 10s|100t>" }
                    val target = online(args[1])
                    val success = plugin.elements.apply(target, element(args[2]), amount(args[3]), (sender as? Player)?.uniqueId, durationTicks(args[4]))
                    reply(sender, if (success) "&aElement applied to ${target.name}." else "&eApplication blocked by immunity, protection or cooldown.")
                }
                "putelement" -> putElement(sender, args, label)
                "give" -> {
                    require(args.size in 2..3) { "Usage: /$label give <itemId> [player]" }
                    val target = args.getOrNull(2)?.let(::online) ?: player(sender)
                    val item = plugin.items.create(args[1])
                    target.inventory.addItem(item).values.forEach { target.world.dropItemNaturally(target.location, it) }
                    reply(sender, "&aGave ${args[1]} to ${target.name}.")
                }
                "removeholo" -> {
                    require(args.size <= 2) { "Usage: /$label removeholo [radius]" }
                    val r = args.getOrNull(1)?.let(::radius) ?: 32.0
                    val count = plugin.holograms.removeNear((sender as? Player)?.location, r)
                    reply(sender, "&aRemoved $count holograms.")
                }
                "armorstatus", "artifactstatus" -> {
                    val target = player(sender)
                    val enabled = if (sub == "armorstatus") plugin.equipment.armorEnabled else plugin.equipment.artifactEnabled
                    require(enabled) { "This effect system is disabled in config.yml." }
                    reply(sender, "&6Effects: " + EquipmentEffect.entries.joinToString(", ") { effect ->
                        "${if (plugin.equipment.active(target, effect)) "&a" else "&7"}${effect.key}"
                    })
                    if (sub == "artifactstatus") reply(sender, "&6Bonuses: " + plugin.equipment.bonuses(target).entries.joinToString(", ") { (element, bonus) -> "&f${element.key} +${bonus.flat} / +${bonus.percent}%" })
                }
                "pos1", "pos2" -> {
                    val target = player(sender)
                    plugin.regions.select(target, if (sub == "pos1") 1 else 2)
                    reply(sender, "&a$sub set at ${target.location.blockX}, ${target.location.blockY}, ${target.location.blockZ}.")
                }
                "createregion" -> {
                    require(args.size == 2) { "Usage: /$label createregion <name>" }
                    plugin.regions.create(player(sender), args[1])
                    reply(sender, "&aRegion '${args[1]}' created across the world's full height.")
                }
                "bonuselement" -> bonus(sender, args, label)
            }
        } catch (ex: IllegalArgumentException) {
            reply(sender, "&c${ex.message}")
        } catch (ex: Exception) {
            plugin.logger.log(Level.WARNING, "Command '$sub' failed", ex)
            reply(sender, "&cCommand failed: ${ex.message}. See the server log.")
        }
        return true
    }

    private fun putElement(sender: CommandSender, args: Array<out String>, label: String) {
        require(args.size in 5..6) { "Usage: /$label putelement <element> <target|area|coneinfrontme> <distance|radius> <entities|player> [amount]" }
        val source = player(sender)
        val element = element(args[1])
        val mode = args[2].lowercase(Locale.ROOT)
        val range = radius(args[3])
        val players = when (args[4].lowercase(Locale.ROOT)) {
            "player", "players" -> true
            "entities", "entity", "mobs" -> false
            else -> throw IllegalArgumentException("Target filter must be 'entities' or 'player'.")
        }
        val amount = args.getOrNull(5)?.let(::amount) ?: 1
        val targets = when (mode) {
            "target" -> {
                val result = source.world.rayTrace(source.eyeLocation, source.eyeLocation.direction, range, FluidCollisionMode.NEVER, true, 0.3) { entity ->
                    entity is LivingEntity && entity.uniqueId != source.uniqueId && (entity is Player) == players &&
                        (plugin.protection.canAffect(entity, source.uniqueId) || plugin.blooms.isCore(entity.uniqueId))
                }
                listOfNotNull(result?.hitEntity as? LivingEntity)
            }
            "area" -> plugin.protection.nearby(source.location, range, source.uniqueId, players)
            "coneinfrontme" -> plugin.protection.nearby(source.location, range, source.uniqueId, players).filter { target ->
                val direction = target.eyeLocation.toVector().subtract(source.eyeLocation.toVector())
                direction.lengthSquared() > 1e-6 && direction.normalize().dot(source.eyeLocation.direction) >= 0.5 && source.hasLineOfSight(target)
            }
            else -> throw IllegalArgumentException("Mode must be target, area or coneinfrontme.")
        }
        val applied = targets.count { plugin.elements.apply(it, element, amount, source.uniqueId) }
        reply(sender, "&aApplied ${element.displayName} to $applied target(s).")
    }

    private fun bonus(sender: CommandSender, args: Array<out String>, label: String) {
        require(args.size in 4..6) { "Usage: /$label bonuselement <element> <multiplier> <playerName|player name|players radius> [duration]" }
        val element = element(args[1])
        val multiplier = requireNotNull(args[2].replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }) { "Multiplier must be finite and non-negative." }
        val targets: List<Player>
        val duration: Long
        when (args[3].lowercase(Locale.ROOT)) {
            "player" -> {
                require(args.size >= 5) { "Specify the player name." }
                targets = listOf(online(args[4])); duration = args.getOrNull(5)?.let(::durationTicks) ?: 900L
            }
            "players" -> {
                val source = player(sender)
                val r = args.getOrNull(4)?.let(::radius) ?: 10.0
                targets = source.world.players.filter { it.location.distanceSquared(source.location) <= r * r }
                duration = args.getOrNull(5)?.let(::durationTicks) ?: 900L
            }
            else -> {
                require(args.size <= 5) { "Too many arguments." }
                targets = listOf(online(args[3])); duration = args.getOrNull(4)?.let(::durationTicks) ?: 900L
            }
        }
        require(duration <= 1728000) { "Maximum bonus duration is 24 hours." }
        targets.forEach { plugin.equipment.addTemporary(it, element, multiplier, duration) }
        reply(sender, "&aApplied ${element.key} x$multiplier for ${duration / 20.0}s to ${targets.size} player(s).")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.isEmpty()) return emptyList()
        val sub = args[0].lowercase(Locale.ROOT)
        if (args.size > 1 && !allowed(sender, sub)) return emptyList()
        val elements = Element.entries.map { it.key }
        val players = Bukkit.getOnlinePlayers().map { it.name }
        val candidates = when {
            args.size == 1 -> permissions.keys.filter { allowed(sender, it) }
            sub == "element" -> when (args.size) { 2 -> players; 3 -> elements; 4 -> listOf("1", "5", "10"); 5 -> listOf("5s", "10s", "100t"); else -> emptyList() }
            sub == "putelement" -> when (args.size) { 2 -> elements; 3 -> listOf("target", "area", "coneinfrontme"); 4 -> listOf("3", "6", "12"); 5 -> listOf("entities", "player"); 6 -> listOf("1", "5", "10"); else -> emptyList() }
            sub == "give" -> when (args.size) { 2 -> plugin.items.definitions.keys.toList(); 3 -> players; else -> emptyList() }
            sub == "removeholo" && args.size == 2 -> listOf("8", "16", "32", "64")
            sub == "bonuselement" -> when (args.size) { 2 -> elements; 3 -> listOf("1.0", "1.25", "1.5", "2.0"); 4 -> players + "players"; 5 -> if (args[3].equals("player", true)) players else listOf("10", "30s", "60s"); 6 -> listOf("30s", "60s", "120s"); else -> emptyList() }
            else -> emptyList()
        }
        return candidates.filter { it.startsWith(args.last(), true) }.sorted()
    }
}
