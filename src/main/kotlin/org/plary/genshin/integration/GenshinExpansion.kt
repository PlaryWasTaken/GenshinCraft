package org.plary.genshin.integration

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import org.plary.genshin.Genshin
import org.plary.genshin.element.Element
import org.plary.genshin.element.Reaction
import org.plary.genshin.util.legacyText
import org.plary.genshin.util.roundedAmount
import java.util.Locale

class GenshinExpansion(private val plugin: Genshin) : PlaceholderExpansion() {
    override fun getIdentifier() = "genshin"
    override fun getAuthor() = "Plary"
    override fun getVersion() = plugin.pluginMeta.version
    override fun persist() = true

    private fun text(path: String, fallback: String): String = plugin.placeholderConfig.getString("placeholders.$path",
        plugin.config.getString("placeholders.$path", fallback))!!

    override fun onRequest(offlinePlayer: OfflinePlayer?, params: String): String? {
        val key = params.lowercase(Locale.ROOT)
        for (category in listOf("mult", "pattern", "text")) {
            val prefix = "reaction_${category}_"
            if (!key.startsWith(prefix)) continue
            val reaction = Reaction.entries.firstOrNull { it.key == key.removePrefix(prefix) } ?: return null
            val multiplier = format(plugin.damage.multiplier(reaction))
            val pattern = text("reactions.${reaction.key}.pattern", reaction.label)
            return when (category) {
                "mult" -> multiplier
                "pattern" -> legacyText(pattern)
                else -> legacyText(text("reactions.${reaction.key}.text", text("formulas.default", "{pattern} &7x{mult}"))
                    .replace("{pattern}", pattern).replace("{mult}", multiplier))
            }
        }
        val player = offlinePlayer?.player ?: return ""
        val state = plugin.elements.state(player.uniqueId)
        val weapon = plugin.items.weapon(player.inventory.itemInMainHand)
        val currentElement = state?.element ?: weapon?.element
        val currentAmount = state?.amount ?: weapon?.amount ?: 0
        val bonuses = plugin.equipment.bonuses(player)
        val artifact = bonuses.entries.maxByOrNull { it.value.flat + it.value.percent }
        fun symbol(element: Element?) = element?.let { text("elements.symbols.${it.key}", it.symbol) }.orEmpty()
        fun composed(template: String, element: Element?, amount: Int): String = legacyText(template
            .replace("{symbol}", symbol(element)).replace("{element}", element?.key ?: "none").replace("{amount}", "$amount"))

        // Resolve exact names before element_* families, so element_symbol/text/amount are reachable.
        when (key) {
            "element" -> return currentElement?.key ?: legacyText(text("defaults.no-element", "none"))
            "element_symbol" -> return legacyText(symbol(currentElement))
            "element_amount" -> return "$currentAmount"
            "element_text" -> return composed(text("elements.texts.current", "{symbol} {element} x{amount}"), currentElement, currentAmount)
            "weapon_element" -> return weapon?.element?.key ?: legacyText(text("defaults.no-weapon-element", "none"))
            "weapon_element_symbol" -> return legacyText(symbol(weapon?.element))
            "weapon_element_amount" -> return "${weapon?.amount ?: 0}"
            "weapon_element_text" -> return composed(text("elements.texts.weapon", "{symbol} {element} x{amount}"), weapon?.element, weapon?.amount ?: 0)
            "artifact_bonus_element" -> return artifact?.key?.key ?: legacyText(text("defaults.no-artifact-bonus", "none"))
            "artifact_bonus_amount" -> return "${roundedAmount(artifact?.value?.flat ?: 0.0)}"
            "artifact_bonus_text" -> return composed(text("elements.texts.artifact_bonus", "{element} +{amount}"), artifact?.key, roundedAmount(artifact?.value?.flat ?: 0.0))
            "bonuselement_active" -> return plugin.equipment.hasTemporary(player).toString()
            "aplication_total_element_in_hand" -> return weapon?.let { "${plugin.equipment.total(player, it.element, it.amount, it.element == Element.ELEMENTAL)}" } ?: "0"
        }
        for (prefix in listOf("aplication_element_", "application_element_", "element_amount_", "element_text_", "bonus_element_", "element_")) {
            if (!key.startsWith(prefix)) continue
            val element = Element.parse(key.removePrefix(prefix)) ?: return null
            val base = if (state?.element == element) state.amount else if (weapon?.element == element) weapon.amount else 0
            val amount = roundedAmount(base + plugin.integrations.perLevel(player, element))
            return when (prefix) {
                "aplication_element_", "application_element_" -> "${plugin.equipment.total(player, element, if (weapon?.element == element || weapon?.element == Element.ELEMENTAL) weapon.amount else 0, weapon?.element == Element.ELEMENTAL)}"
                "element_amount_" -> "$amount"
                "bonus_element_" -> {
                    val multiplier = plugin.equipment.temporary(player, element)?.multiplier ?: 1.0
                    "${roundedAmount(base * (multiplier - 1.0))}"
                }
                else -> composed(text("elements.texts.per_element.${element.key}", "{symbol} {element} x{amount}"), element, amount)
            }
        }
        return null
    }

    private fun format(value: Double): String = if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
