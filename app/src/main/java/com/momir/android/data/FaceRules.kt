package com.momir.android.data

/**
 * Which faces of a multi-face card are printed. Shared by the Classic creature list (MTGJSON)
 * and the custom format card pool (Scryfall); both use the same layout names.
 */
object FaceRules {
    /** Layouts whose second face has its own artwork; the others share a single image. */
    val SEPARATE_ART_LAYOUTS = setOf("transform", "reversible_card")

    /**
     * A Momir token is a copy of the card, so another face only matters if the token can use
     * it. A token copy of a transforming card can transform, and a prepare spell is part of
     * the copiable values, so those keep their other face. The faces of these layouts can
     * only be used from a card (adventure, omen, modal), are not part of a token (meld,
     * split, aftermath) or repeat the front (reversible), so only the front is printed.
     * Layouts that are not listed keep their faces, so a new mechanic is not dropped silently.
     */
    val LAYOUTS_WITHOUT_USABLE_OTHER_FACE = setOf(
        "adventure",
        "omen",
        "modal_dfc",
        "meld",
        "split",
        "aftermath",
        "reversible_card",
    )
}
