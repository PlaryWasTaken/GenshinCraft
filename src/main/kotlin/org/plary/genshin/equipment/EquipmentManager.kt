package org.plary.genshin.equipment

import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.plary.genshin.Genshin
import org.plary.genshin.element.Element
import org.plary.genshin.util.*
import java.util.UUID
import kotlin.random.Random

data class Bonus(val flat: Double = 0.0, val percent: Double = 0.0) {
    operator fun plus(other: Bonus) = Bonus(flat + other.flat, percent + other.percent)
    fun apply(base: Int, scale: Double = 1.0): Int = roundedAmount(base + (flat + base * percent / 100.0) * scale)
}

enum class EquipmentEffect(val key: String, val defaultLore: String) {
    PADDED("padded", "&fPadded"), WATERPROOF("waterproof", "&3Waterproof"),
    FIREPROOF("fireproof", "&cFireproof"), ANTISTATIC("antistatic", "&5Antistatic")
}

data class TemporaryBonus(val multiplier: Double, val expiresAt: Long)

class EquipmentManager(private val plugin: Genshin) {
    private data class BonusPattern(val element: Element, val regex: Regex)
    private var patterns = emptyList<BonusPattern>()
    private var mapping = emptyMap<Int, String>()
    private val temporary = mutableMapOf<UUID, MutableMap<Element, TemporaryBonus>>()
    val artifactEnabled get() = plugin.config.getBoolean("effects.artifact", false)
    val armorEnabled get() = plugin.config.getBoolean("effects.armor", true) && !artifactEnabled
    val usesSlots get() = artifactEnabled && plugin.config.getBoolean("artifact.use-inventory-slots", false)

    fun reload() {
        patterns = plugin.config.getStringList("artifact.bonus.lore").filter { "<x>" in it }.map { raw ->
            val element = Element.base.firstOrNull { it.key in raw.lowercase() } ?: Element.ELEMENTAL
            BonusPattern(element, pattern(raw, "<x>", "([0-9]+(?:[.,][0-9]+)?%?)"))
        }
        val defaults = listOf("padded", "waterproof", "fireproof", "antistatic", "bonus")
        mapping = (1..5).associateWith { position ->
            unaccent(plugin.config.getString("artifact.slots.$position", defaults[position - 1])!!).replace("antiestatico", "antistatic")
        }
    }

    private fun pattern(raw: String, token: String, valueRegex: String): Regex {
        val parts = normalizedName(raw).split(token)
        return Regex("^" + parts.joinToString(valueRegex) { Regex.escape(it) } + "$")
    }

    @Suppress("DEPRECATION")
    private fun lore(item: ItemStack?): List<String> = item?.itemMeta?.lore?.map(::normalizedName).orEmpty()

    fun candidateItems(player: Player, key: String = "bonus"): List<ItemStack> = when {
        !artifactEnabled -> emptyList()
        usesSlots -> mapping.filterValues { it == key }.keys.mapNotNull { player.inventory.getItem(8 + it) }
        else -> player.inventory.contents.filterNotNull() // Contents already includes the offhand exactly once.
    }

    fun active(player: Player, effect: EquipmentEffect): Boolean {
        val system = if (artifactEnabled) "artifact" else if (armorEnabled) "armor" else return false
        val expected = normalizedName(plugin.config.getString("$system.lores.${effect.key}", effect.defaultLore)!!)
        if (expected.isBlank()) return false
        return if (artifactEnabled) candidateItems(player, effect.key).any { expected in lore(it) }
        else player.inventory.armorContents.let { pieces -> pieces.size == 4 && pieces.all { it != null && expected in lore(it) } }
    }

    fun bonuses(player: Player): Map<Element, Bonus> = buildMap {
        for (item in candidateItems(player)) for (line in lore(item)) for (pattern in patterns) {
            val match = pattern.regex.matchEntire(line) ?: continue
            val raw = match.groupValues[1]
            val value = number(raw.removeSuffix("%"))
            val bonus = if (raw.endsWith('%')) Bonus(percent = value) else Bonus(flat = value)
            put(pattern.element, (get(pattern.element) ?: Bonus()) + bonus)
        }
    }

    fun total(player: Player, element: Element, base: Int, randomWeapon: Boolean = false): Int {
        val bonuses = bonuses(player)
        val global = bonuses[Element.ELEMENTAL] ?: Bonus()
        val adjusted = if (randomWeapon) global.apply(base, 0.5)
        else (global + (bonuses[element] ?: Bonus())).apply(base)
        val multiplier = temporary(player, element)?.multiplier ?: 1.0
        return roundedAmount(adjusted * multiplier + plugin.integrations.perLevel(player, element))
    }

    fun temporary(player: Player, element: Element): TemporaryBonus? = temporary[player.uniqueId]?.get(element)?.takeIf { it.expiresAt > plugin.ticks }
    fun hasTemporary(player: Player): Boolean = temporary[player.uniqueId]?.values?.any { it.expiresAt > plugin.ticks } == true

    fun addTemporary(player: Player, element: Element, multiplier: Double, duration: Long) {
        require(multiplier.isFinite() && multiplier >= 0.0) { "Multiplier must be a finite, non-negative number." }
        temporary.getOrPut(player.uniqueId) { mutableMapOf() }[element] = TemporaryBonus(multiplier, Math.addExact(plugin.ticks, duration))
    }

    fun cleanup() {
        temporary.values.forEach { it.values.removeIf { bonus -> bonus.expiresAt <= plugin.ticks } }
        temporary.values.removeIf { it.isEmpty() }
    }

    fun clear(player: UUID) { temporary.remove(player) }
    fun clear() { temporary.clear() }

    fun blocks(player: Player, element: Element, ambient: Boolean): Boolean {
        val effects = when (element) {
            Element.PYRO -> listOf(EquipmentEffect.FIREPROOF)
            Element.ELECTRO -> listOf(EquipmentEffect.ANTISTATIC)
            Element.ANEMO, Element.GEO -> listOf(EquipmentEffect.PADDED)
            Element.CRYO -> if (ambient) listOf(EquipmentEffect.PADDED) else listOf(EquipmentEffect.WATERPROOF)
            Element.HYDRO, Element.DENDRO -> listOf(EquipmentEffect.WATERPROOF)
            Element.ELEMENTAL -> emptyList()
        }
        if (effects.none { active(player, it) }) return false
        if (ambient) return true
        val probability = plugin.config.numeric("protection.elementAvoid.baseChance", plugin.config.numeric("protections.base-chance-avoid-element-from-weapons-and-commands", 0.15)).coerceIn(0.0, 1.0)
        if (Random.nextDouble() >= probability) return false
        val message = plugin.config.getString("protection.elementAvoid.message", plugin.config.getString("messages.avoid-element-armors-artifacts", "&7You avoided {element}."))!!
        if (message.isNotBlank()) player.sendMessage(component(message.replace("{element}", element.displayName)))
        return true
    }

    private fun runeValue(item: ItemStack, path: String): String? {
        val raw = plugin.config.getString(path) ?: return null
        if ("{amount}" !in raw) return null
        val regex = pattern(raw, "{amount}", "([0-9]+(?:[.,][0-9]+)?%?)")
        return lore(item).firstNotNullOfOrNull { regex.matchEntire(it)?.groupValues?.get(1) }
    }

    fun counterattack(victim: Player, attacker: LivingEntity) {
        if (!artifactEnabled || attacker.uniqueId == victim.uniqueId || !plugin.protection.canAffect(attacker, victim.uniqueId)) return
        for (explosion in listOf(false, true)) {
            val key = if (explosion) "explosion" else "counter"
            val rune = candidateItems(victim).firstOrNull { runeValue(it, "inventory.artifacts.$key-apply-lore") != null } ?: continue
            val probability = chance(runeValue(rune, "inventory.artifacts.$key-apply-lore")!!)
            if (Random.nextDouble() >= probability) continue
            val weapon = plugin.items.weapon(victim.inventory.itemInMainHand)
            @Suppress("DEPRECATION") val name = rune.itemMeta?.displayName.orEmpty().lowercase()
            val bonusMap = bonuses(victim)
            val element = Element.base.firstOrNull { it.key in name }
                ?: Element.base.maxBy { (bonusMap[it] ?: Bonus()).flat + (bonusMap[it] ?: Bonus()).percent }.takeIf { (bonusMap[it] ?: Bonus()) != Bonus() }
                ?: weapon?.element?.takeIf { it != Element.ELEMENTAL }
                ?: Element.PYRO
            val base = if (weapon?.element == element || weapon?.element == Element.ELEMENTAL) weapon.amount else 0
            val total = total(victim, element, base)
            val fraction = if (explosion) number(plugin.config.getString("inventory.artifacts.explosion-application-element", "50%")!!.removeSuffix("%"), 50.0).coerceIn(0.0, 100.0) / 100.0 else 1.0
            val amount = roundedAmount(total * fraction)
            if (amount <= 0) continue
            val radius = number(runeValue(rune, "inventory.artifacts.explosion-radius-lore"), plugin.config.numeric("inventory.artifacts.explosion-default-radius", 3.5)).coerceIn(0.1, 64.0)
            val targets = if (explosion) plugin.protection.nearby(victim.location, radius, victim.uniqueId) else listOf(attacker)
            var applied = 0
            for (target in targets) if (plugin.elements.apply(target, element, amount, victim.uniqueId, adjustBonus = false)) applied++
            if (applied > 0) {
                val template = plugin.config.getString("inventory.artifacts.messages.$key", "&7Applied {amount} {element}.")!!
                victim.sendMessage(component(template.replace("{amount}", "$amount").replace("{element}", element.displayName).replace("{radius}", "$radius")))
            }
        }
    }
}
