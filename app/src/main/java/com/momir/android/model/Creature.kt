package com.momir.android.model

/** One face of a multi-face card (transform, modal double-faced, adventure, split, ...). */
data class CardFace(
    val name: String,
    val manaCost: String,
    val type: String,
    val power: String,
    val toughness: String,
    val text: String,
)

data class Creature(
    val name: String,
    val manaValue: Int,
    val type: String,
    val power: String,
    val toughness: String,
    val text: String,
    val manaCost: String,
    val isFunny: Boolean,
    /** Name of the front face. For multi-face cards [name] is the full "Front // Back" name. */
    val displayName: String = name,
    /** The other faces; they are printed below the front face. */
    val extraFaces: List<CardFace> = emptyList(),
    /** True when the second face has its own artwork (transform, modal double-faced). */
    val backHasOwnArt: Boolean = false,
    /** Small print at the bottom of the front face, e.g. the set and rarity of the printing. */
    val footer: String = "",
)
