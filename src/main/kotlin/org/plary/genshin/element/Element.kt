package org.plary.genshin.element

import java.util.Locale

enum class Element(val symbol: String) {
    ANEMO("<white>♨"), CRYO("<aqua>❄"), HYDRO("<blue>🌊"), PYRO("<red>🔥"),
    GEO("<gold>₪"), ELECTRO("<dark_purple>⚡"), DENDRO("<dark_green>☘"), ELEMENTAL("<gold>*");

    val key: String get() = name.lowercase(Locale.ROOT)
    val displayName: String get() = key.replaceFirstChar(Char::uppercase)

    companion object {
        val base = entries.filter { it != ELEMENTAL }
        fun parse(value: String?): Element? = entries.firstOrNull { it.name.equals(value, true) }
    }
}

enum class Reaction(val key: String, val label: String) {
    FREEZE("frozen", "Frozen"), CRYSTALLIZE("crystallize", "Crystallize"),
    MELT("melt", "Melt"), ELECTRO_CHARGED("electrocharged", "Electro-Charged"),
    BURNING("burning", "Burning"), OVERLOAD("overload", "Overload"),
    SUPERCONDUCT("superconduct", "Superconduct"), SWIRL("swirl", "Swirl"),
    VAPORIZE("vaporize", "Vaporize"), QUICKEN("quicken", "Quicken"),
    AGGRAVATE("aggravate", "Aggravate"), SPREAD("spread", "Spread"),
    BLOOM("bloom", "Bloom"), HYPERBLOOM("hyperbloom", "Hyperbloom"),
    BURGEON("burgeon", "Burgeon"), ROOTS("roots", "Roots"),
    LUNAR_CRYSTALLIZE("lunar-crystallize", "Lunar Crystallize"),
    LUNAR_BLOOM("lunar-bloom", "Lunar Bloom"), LUNAR_CHARGED("lunar-charged", "Lunar Charged"),
    STELLAR_CONDUCT("stellar-conduct", "Stellar Conduct"), STELLAR_SWIRL("stellar-swirl", "Stellar Swirl");

    val dendro: Boolean get() = this in setOf(QUICKEN, AGGRAVATE, SPREAD, BLOOM, HYPERBLOOM, BURGEON)

    companion object {
        fun determineSpecial(a: Element, b: Element, lunar: Double, stellar: Double): Reaction? {
            val pair = setOf(a, b)
            if (lunar > 0 && lunar >= stellar) return when (pair) {
                setOf(Element.HYDRO, Element.GEO) -> LUNAR_CRYSTALLIZE
                setOf(Element.HYDRO, Element.DENDRO) -> LUNAR_BLOOM
                setOf(Element.HYDRO, Element.ELECTRO) -> LUNAR_CHARGED
                else -> null
            }
            if (stellar > 0) return when (pair) {
                setOf(Element.CRYO, Element.ELECTRO) -> STELLAR_CONDUCT
                setOf(Element.CRYO, Element.ANEMO) -> STELLAR_SWIRL
                else -> null
            }
            return null
        }
        fun determine(a: Element, b: Element): Reaction? {
            if (a == b || a == Element.ELEMENTAL || b == Element.ELEMENTAL) return null
            val pair = setOf(a, b)
            return when (pair) {
                setOf(Element.HYDRO, Element.CRYO) -> FREEZE
                setOf(Element.PYRO, Element.CRYO) -> MELT
                setOf(Element.ELECTRO, Element.HYDRO) -> ELECTRO_CHARGED
                setOf(Element.PYRO, Element.DENDRO) -> BURNING
                setOf(Element.ELECTRO, Element.PYRO) -> OVERLOAD
                setOf(Element.ELECTRO, Element.CRYO) -> SUPERCONDUCT
                setOf(Element.PYRO, Element.HYDRO) -> VAPORIZE
                setOf(Element.DENDRO, Element.GEO) -> ROOTS
                setOf(Element.DENDRO, Element.ELECTRO) -> QUICKEN
                setOf(Element.DENDRO, Element.HYDRO) -> BLOOM
                else -> when {
                    Element.ANEMO in pair && Element.GEO !in pair && Element.DENDRO !in pair -> SWIRL
                    Element.GEO in pair && Element.ANEMO !in pair && Element.DENDRO !in pair -> CRYSTALLIZE
                    else -> null
                }
            }
        }
    }
}
