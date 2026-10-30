package org.plary.genshin.formula

import java.util.Locale

/** Small arithmetic parser. Hyphens are operators except in the documented legacy aliases. */
object ExpressionEvaluator {
    private val aliases = ElementAliases()

    fun evaluate(expression: String, variables: Map<String, Double>): Double {
        require(expression.length <= 4096) { "Formula is too long" }
        var normalized = expression.lowercase(Locale.ROOT).replace(',', '.')
        aliases.values.forEach { (alias, canonical) ->
            normalized = normalized.replace(Regex("(?<![a-z0-9_])${Regex.escape(alias)}(?![a-z0-9_])"), canonical)
        }
        val vars = variables.mapKeys { (key, _) -> aliases.values[key.lowercase(Locale.ROOT)] ?: key.lowercase(Locale.ROOT) }
        return Parser(normalized, vars).parse()
    }

    private class ElementAliases {
        val values = buildMap {
            listOf("pyro", "hydro", "cryo", "electro", "dendro", "anemo", "geo").forEach {
                put("amount-$it", "amount$it")
            }
            put("pulse-multiplier", "pulse_multiplier")
            put("new-electro", "newElectro")
            put("electro_nuevo", "newElectro")
            put("new-dendro", "newDendro")
            put("dendro_nuevo", "newDendro")
        }
    }

    private class Parser(val text: String, val vars: Map<String, Double>) {
        var pos = 0
        var depth = 0

        fun parse(): Double {
            val value = expression()
            whitespace()
            require(pos == text.length) { "Unexpected token at position $pos" }
            require(value.isFinite()) { "Formula must produce a finite number" }
            return value
        }

        fun expression(): Double {
            var value = term()
            while (true) value = when {
                take('+') -> value + term()
                take('-') -> value - term()
                else -> return value
            }
        }

        fun term(): Double {
            var value = atom()
            while (true) value = when {
                take('*') -> value * atom()
                take('/') -> {
                    val divisor = atom()
                    require(divisor != 0.0) { "Division by zero" }
                    value / divisor
                }
                else -> return value
            }
        }

        fun atom(): Double {
            require(++depth <= 64) { "Formula nesting is too deep" }
            try {
                if (take('+')) return atom()
                if (take('-')) return -atom()
                if (take('(')) {
                    val value = expression()
                    require(take(')')) { "Missing closing parenthesis" }
                    return value
                }
                whitespace()
                val start = pos
                if (pos < text.length && (text[pos].isLetter() || text[pos] == '_')) {
                    while (pos < text.length && (text[pos].isLetterOrDigit() || text[pos] == '_')) pos++
                    val name = text.substring(start, pos)
                    return requireNotNull(vars[name]) { "Unknown variable: $name" }
                }
                while (pos < text.length && (text[pos].isDigit() || text[pos] == '.')) pos++
                return requireNotNull(text.substring(start, pos).toDoubleOrNull()) { "Expected a number at position $start" }
            } finally {
                depth--
            }
        }

        fun take(char: Char): Boolean {
            whitespace()
            if (pos >= text.length || text[pos] != char) return false
            pos++
            return true
        }

        fun whitespace() { while (pos < text.length && text[pos].isWhitespace()) pos++ }
    }
}
