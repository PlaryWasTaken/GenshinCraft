package org.plary.genshin

import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Cow
import org.bukkit.entity.LivingEntity
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.plugin.java.JavaPlugin
import org.plary.genshin.element.Element
import org.plary.genshin.element.Reaction
import org.plary.genshin.formula.ReactionInput
import java.util.logging.Level

/** Optional integration harness: packaged only by paperSmokeJar, never in the production JAR. */
class PaperSmokePlugin : JavaPlugin(), Listener {
    private lateinit var genshin: Genshin
    private var failure = false
    private var cancelDamage = false
    private var count = 0
    private var nextPosition = 0
    private val spawned = mutableListOf<LivingEntity>()
    private val deferred = mutableListOf<Pair<String, () -> Unit>>()

    override fun onEnable() {
        genshin = server.pluginManager.getPlugin("genshin") as Genshin
        server.pluginManager.registerEvents(this, this)
        server.scheduler.runTaskLater(this, Runnable { begin() }, 10)
    }

    @EventHandler fun damage(event: EntityDamageEvent) {
        if (cancelDamage && genshin.damage.dealingReactionDamage) event.isCancelled = true
    }

    private fun verify(name: String, action: () -> Unit) {
        try { action(); count++; logger.info("PASS: $name") }
        catch (ex: Throwable) { failure = true; logger.log(Level.SEVERE, "FAIL: $name", ex) }
    }

    private fun mob(): Cow {
        val world = server.worlds.first()
        val origin = world.spawnLocation
        val location = Location(world, origin.x + (nextPosition++ % 8) * 16, 200.0, origin.z + (nextPosition / 8) * 16)
        world.getChunkAt(location).addPluginChunkTicket(this)
        return world.spawn(location, Cow::class.java) {
            it.setAI(false); it.setGravity(false)
            it.getAttribute(Attribute.MAX_HEALTH)!!.baseValue = 1000.0
            it.health = 1000.0
            it.isPersistent = false
            spawned += it
        }
    }

    private fun apply(target: LivingEntity, element: Element, amount: Int = 1) {
        check(genshin.elements.apply(target, element, amount)) { "Could not apply $element" }
    }

    private fun core(target: LivingEntity): LivingEntity {
        val existing = target.world.livingEntities.filter { genshin.blooms.isCore(it.uniqueId) }.map { it.uniqueId }.toSet()
        apply(target, Element.DENDRO); apply(target, Element.HYDRO)
        return target.world.livingEntities.first { genshin.blooms.isCore(it.uniqueId) && it.uniqueId !in existing }
    }

    private fun begin() {
        try {
            val cfg = genshin.config
            cfg.set("weapons-element-logs", false)
            cfg.set("elements.apply-cooldown-ticks", 0)
            cfg.set("reactions.global-icd-ticks", 0)
            cfg.set("reactions.hit-icd-ticks", 0)
            for (family in listOf("cooldown", "cooldown-dendro")) for (suffix in listOf("ticks", "players-ticks", "entities-ticks")) cfg.set("reactions.$family-$suffix", 0)
            cfg.set("holograms.optimize.enabled", false)
            cfg.set("formulas.ModifyFormulas", true)
            cfg.set("formulas.crystallize", "hydro * 2 + geo")
            for (name in listOf("crystallize", "roots")) cfg.set("parameters.$name.duration-ticks", 8)
            for (name in listOf("electrocharged", "burning")) {
                cfg.set("parameters.$name.ticks", 2); cfg.set("parameters.$name.interval-ticks", 2)
            }
            cfg.set("parameters.bloom.duration-ticks", 12)
            cfg.set("reactions.multipliers.vaporize", 1.5)
            cfg.set("reactions.multipliers.vaporize-players", 0.5)
            cfg.set("states.lunar-multiplier", 1.0)

            verify("lunar crystallize creates three displays and fires") {
                val target = mob()
                val before = target.world.entities.count { genshin.isTemporary(it) }
                check(genshin.elements.apply(target, Element.HYDRO, 1, lunar = 1.0))
                check(genshin.elements.apply(target, Element.GEO, 1))
                check(target.world.entities.count { genshin.isTemporary(it) } >= before + 3)
                deferred += "lunar crystallize shots hit" to { check(target.health < 1000.0) }
            }

            verify("stellar conduct creates physical resistance reduction") {
                val target = mob()
                check(genshin.elements.apply(target, Element.CRYO, 1, stellar = 1.0))
                check(genshin.elements.apply(target, Element.ELECTRO, 1))
                check(genshin.specialReactions.physicalMultiplier(target) == 1.2)
                deferred += "stellar conduct strikes mobs" to { check(target.health < 1000.0) }
            }

            verify("all direct reaction pairs execute on Paper") {
                val pairs = listOf(Element.HYDRO to Element.CRYO, Element.PYRO to Element.CRYO,
                    Element.ELECTRO to Element.HYDRO, Element.PYRO to Element.DENDRO,
                    Element.ELECTRO to Element.PYRO, Element.ELECTRO to Element.CRYO,
                    Element.PYRO to Element.HYDRO, Element.ANEMO to Element.PYRO,
                    Element.GEO to Element.PYRO, Element.DENDRO to Element.GEO,
                    Element.DENDRO to Element.ELECTRO)
                pairs.forEach { (a, b) ->
                    val target = mob()
                    apply(target, a); apply(target, b)
                    check(genshin.elements.state(target.uniqueId) == null) { "$a + $b left a base state" }
                }
            }

            verify("reaction damage respects event cancellation") {
                val target = mob()
                cancelDamage = true
                try { apply(target, Element.HYDRO); apply(target, Element.PYRO) }
                finally { cancelDamage = false }
                check(target.health == 1000.0) { "Cancelled damage changed health" }
            }

            verify("reaction damage bypasses vanilla hit immunity without changing its timer") {
                val target = mob()
                target.noDamageTicks = 20
                apply(target, Element.HYDRO); apply(target, Element.PYRO)
                check(target.health < 1000.0)
                check(target.noDamageTicks == 20)
            }

            verify("delayed areas retain formula inputs") {
                val target = mob()
                apply(target, Element.GEO, 2); apply(target, Element.HYDRO, 9)
                deferred += "delayed Crystallize formula" to { check(target.health == 980.0) { "Expected health 980, got ${target.health}" } }
            }

            verify("Aggravate and Spread damage the triggering target") {
                for (element in listOf(Element.ELECTRO, Element.DENDRO)) {
                    val target = mob()
                    apply(target, Element.DENDRO); apply(target, Element.ELECTRO)
                    apply(target, element, 3)
                    check(target.health < 1000.0)
                }
            }

            verify("Bloom and Burgeon create and consume a core") {
                val target = mob()
                val seed = core(target)
                check(genshin.blooms.activate(seed.uniqueId, Element.PYRO, 2, null))
                check(!seed.isValid && !genshin.blooms.isCore(seed.uniqueId))
                check(target.health < 1000.0)
            }

            verify("Hyperbloom launches homing missiles") {
                val target = mob()
                val seed = core(target)
                check(genshin.blooms.activate(seed.uniqueId, Element.ELECTRO, 3, null))
                deferred += "Hyperbloom missile impact" to { check(target.health < 1000.0) { "Missile never hit" } }
            }

            verify("Bloom expires with configured explosion damage") {
                val target = mob()
                val seed = core(target)
                seed.damage(20.0)
                check(seed.isValid && !seed.isDead) { "A core took ordinary damage" }
                deferred += "Bloom expiry" to { check(!seed.isValid && target.health < 1000.0) }
            }

            verify("Bloom survives the original target's death") {
                val target = mob()
                val seed = core(target)
                target.health = 0.0
                check(genshin.blooms.isCore(seed.uniqueId))
            }

            verify("player damage multiplier and celestial formulas stay consistent") {
                val input = ReactionInput(Element.PYRO, 10, Element.HYDRO, 10, lunar = 20.0)
                check(genshin.damage.calculate(Reaction.VAPORIZE, input, true) == 12.0)
                check(genshin.damage.calculate(Reaction.VAPORIZE, input, false) == 36.0)
            }
        } catch (ex: Throwable) { failure = true; logger.log(Level.SEVERE, "Smoke setup failed", ex) }
        server.scheduler.runTaskLater(this, Runnable { finish() }, 50)
    }

    private fun finish() {
        deferred.forEach { (name, action) -> verify(name, action) }
        verify("disable removes every temporary entity") {
            server.pluginManager.disablePlugin(genshin)
            check(server.worlds.flatMap { it.entities }.none(genshin::isTemporary))
        }
        spawned.filter { it.isValid }.forEach { it.remove() }
        logger.info(if (failure) "PAPER_SMOKE_FAILED ($count checks passed)" else "PAPER_SMOKE_PASSED ($count checks)")
        server.shutdown()
    }
}
