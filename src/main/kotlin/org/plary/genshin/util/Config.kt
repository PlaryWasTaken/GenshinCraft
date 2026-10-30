package org.plary.genshin.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.configuration.ConfigurationSection
import java.text.Normalizer
import java.util.Locale

private val legacy = LegacyComponentSerializer.builder().character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build()
private val sectionLegacy = LegacyComponentSerializer.legacySection()

private val miniMessage = MiniMessage.miniMessage()
private val miniTags = Regex("<(?:/?(?:black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|dark_gray|blue|green|aqua|red|light_purple|yellow|white|bold|italic|underlined|strikethrough|obfuscated|reset|gradient|rainbow|color)|#[0-9a-fA-F]{6})(?::[^>]*)?>", RegexOption.IGNORE_CASE)
private val legacyCodes = Regex("[&§](#[0-9a-fA-F]{6}|[0-9a-fk-or])", RegexOption.IGNORE_CASE)
private val codeTags = mapOf('0' to "black", '1' to "dark_blue", '2' to "dark_green", '3' to "dark_aqua", '4' to "dark_red", '5' to "dark_purple", '6' to "gold", '7' to "gray", '8' to "dark_gray", '9' to "blue", 'a' to "green", 'b' to "aqua", 'c' to "red", 'd' to "light_purple", 'e' to "yellow", 'f' to "white", 'k' to "obfuscated", 'l' to "bold", 'm' to "strikethrough", 'n' to "underlined", 'o' to "italic", 'r' to "reset")
fun component(text: String): Component = if (miniTags.containsMatchIn(text)) {
    miniMessage.deserialize(legacyCodes.replace(text) { match ->
        val code = match.groupValues[1].lowercase(Locale.ROOT)
        "<${if (code.startsWith('#')) code else codeTags[code[0]]}>"
    })
} else legacy.deserialize(text.replace('§', '&'))
fun legacyText(text: String): String = sectionLegacy.serialize(component(text))
fun normalizedName(text: String): String = legacyText(text)
fun unaccent(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)

fun number(value: Any?, default: Double = 0.0): Double {
    val parsed = when (value) {
        is Number -> value.toDouble()
        is String -> value.trim().lowercase(Locale.ROOT).removePrefix("x").removeSuffix("x").replace(',', '.').toDoubleOrNull()
        else -> null
    }
    return parsed?.takeIf(Double::isFinite) ?: default
}

fun ConfigurationSection.numeric(path: String, default: Double = 0.0): Double = number(get(path), default)
fun ConfigurationSection.flag(path: String, default: Boolean = false): Boolean = when (val value = get(path)) {
    is Boolean -> value
    is Number -> value.toInt() != 0
    is String -> value.lowercase(Locale.ROOT) in setOf("true", "yes", "on", "1")
    else -> default
}

fun durationTicks(value: String): Long {
    val text = value.lowercase(Locale.ROOT)
    val factor = if (text.endsWith('t')) 1L else 20L
    val amount = text.removeSuffix("t").removeSuffix("s").toLongOrNull()
    require(amount != null && amount > 0 && amount <= Long.MAX_VALUE / factor) { "Duration must be positive (for example 10s or 100t)." }
    return amount * factor
}

fun roundedAmount(value: Double): Int = Math.round(value.coerceIn(0.0, Int.MAX_VALUE.toDouble())).toInt()

/** Percentages in lore accept 10%, 10, or 0.1. An explicit % always means percent. */
fun chance(value: String): Double {
    val amount = number(value.replace("%", ""))
    return (if ('%' in value || amount > 1) amount / 100.0 else amount).coerceIn(0.0, 1.0)
}
