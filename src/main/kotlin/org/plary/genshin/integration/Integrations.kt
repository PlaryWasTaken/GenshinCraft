package org.plary.genshin.integration

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.plary.genshin.Genshin
import org.plary.genshin.element.Element
import org.plary.genshin.util.numeric
import java.util.Locale

/** Optional APIs stay behind this boundary, so none are needed to load the plugin. */
class Integrations(private val plugin: Genshin) {
    private val flags = mutableMapOf<String, Any>()
    private val warned = mutableSetOf<String>()

    private fun call(receiver: Any, name: String, vararg args: Any?): Any? = receiver.javaClass.methods.first {
        it.name == name && it.parameterCount == args.size && it.parameterTypes.zip(args).all { (type, arg) ->
            arg == null || type.isInstance(arg)
        }
    }.invoke(receiver, *args)

    private fun worldGuard(): Any {
        val loader = requireNotNull(Bukkit.getPluginManager().getPlugin("WorldGuard")).javaClass.classLoader
        return Class.forName("com.sk89q.worldguard.WorldGuard", true, loader).getMethod("getInstance").invoke(null)
    }

    fun registerFlags() {
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) return
        try {
            val wg = worldGuard()
            val registry = call(wg, "getFlagRegistry")!!
            val stateFlag = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag", true, wg.javaClass.classLoader)
            for (name in listOf("genshin-ambient-storm", "pyro-heat", "dendro-breeze", "geo-storm")) {
                val existing = call(registry, "get", name)
                if (existing != null) {
                    require(stateFlag.isInstance(existing)) { "WorldGuard flag '$name' has an incompatible type" }
                    flags[name] = existing
                } else {
                    val flag = stateFlag.getConstructor(String::class.java, Boolean::class.javaPrimitiveType).newInstance(name, false)
                    call(registry, "register", flag)
                    flags[name] = flag
                }
            }
        } catch (ex: Exception) { warn("WorldGuard flags", ex) }
    }

    private fun regionSet(location: Location): Any {
        val wg = worldGuard()
        val adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", true, wg.javaClass.classLoader)
        val adapted = adapter.getMethod("adapt", Location::class.java).invoke(null, location)
        val platform = call(wg, "getPlatform")!!
        return call(call(call(platform, "getRegionContainer")!!, "createQuery")!!, "getApplicableRegions", adapted)!!
    }

    private val wgEnabled get() = Bukkit.getPluginManager().isPluginEnabled("WorldGuard")

    fun pvpAllowed(location: Location): Boolean {
        if (!wgEnabled) return true
        return try {
            val wg = worldGuard()
            val flag = Class.forName("com.sk89q.worldguard.protection.flags.Flags", true, wg.javaClass.classLoader).getField("PVP").get(null)
            call(regionSet(location), "queryValue", null, flag)?.toString() != "DENY"
        } catch (ex: Exception) {
            warn("WorldGuard PvP query", ex)
            false
        }
    }

    fun ambientAllowed(location: Location, name: String): Boolean {
        if (!wgEnabled || !plugin.config.getBoolean("regions.restrictions.wg-default-block", true)) return true
        return try {
            val set = regionSet(location)
            if ((call(set, "size") as Number).toInt() == 0) true
            else listOf(name, "genshin-ambient-storm").any {
                flags[it]?.let { flag -> call(set, "queryValue", null, flag)?.toString() == "ALLOW" } == true
            }
        } catch (ex: Exception) {
            warn("WorldGuard ambient query", ex)
            false
        }
    }

    fun ambientForced(location: Location, name: String): Boolean {
        if (!wgEnabled) return false
        val flag = flags[name] ?: return false
        return try {
            call(regionSet(location), "queryValue", null, flag)?.toString() == "ALLOW"
        } catch (ex: Exception) {
            warn("WorldGuard ambient flag", ex)
            false
        }
    }

    fun perLevel(player: Player, element: Element): Double {
        if (!Bukkit.getPluginManager().isPluginEnabled("MMOCore")) return 0.0
        return try {
            val loader = Bukkit.getPluginManager().getPlugin("MMOCore")!!.javaClass.classLoader
            val dataClass = Class.forName("net.Indyuce.mmocore.api.player.PlayerData", true, loader)
            val data = dataClass.getMethod("get", OfflinePlayer::class.java).invoke(null, player)
            val profession = call(call(data, "getProfess")!!, "getId").toString().lowercase(Locale.ROOT)
            val level = (call(data, "getLevel") as Number).toDouble()
            val cfg = plugin.classBonuses
            val base = "classes.$profession.${element.key}"
            val alt = "clases.$profession.${element.key}"
            (cfg.numeric("$base.per-level", cfg.numeric("$alt.per-level", cfg.numeric(base, cfg.numeric(alt)))) * level).coerceAtLeast(0.0)
        } catch (ex: Exception) {
            warn("MMOCore level bonus", ex)
            0.0
        }
    }

    private fun warn(context: String, ex: Exception) {
        if (warned.add(context)) plugin.logger.warning("$context failed: ${ex.cause?.message ?: ex.message}")
    }
}
