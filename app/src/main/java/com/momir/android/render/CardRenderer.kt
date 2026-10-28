package com.momir.android.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.momir.android.model.Creature

object CardRenderer {
    private const val PADDING = 12f
    private const val NAME_MANA_GAP = 12f
    private const val MIN_WRAP_WIDTH = 60f
    private const val MIN_NAME_SHARE = 0.4f
    private const val COMPACT_MANA = false

    fun renderCardBitmap(creature: Creature, artBitmap: Bitmap?, printWidth: Int = 576): Bitmap {
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 36f
            color = Color.BLACK
            typeface = Typeface.DEFAULT_BOLD
        }
        val typePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 26f
            color = Color.BLACK
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 24f
            color = Color.BLACK
        }
        val ptPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 34f
            color = Color.BLACK
            typeface = Typeface.DEFAULT_BOLD
        }
        val rulePaint = Paint().apply { color = Color.BLACK; strokeWidth = 1f }

        val contentWidth = printWidth - (PADDING * 2)
        val nameHeight = 44f
        val typeHeight = 32f
        val textLineHeight = 28f
        val nameLineHeight = 40f
        val typeLineHeight = 30f
        val ptHeight = if (creature.power.isNotBlank() && creature.toughness.isNotBlank()) 42f else 0f

        val manaText = if (COMPACT_MANA) compactMana(creature.manaCost) else creature.manaCost
        val manaWidth = if (manaText.isNotBlank()) namePaint.measureText(manaText) else 0f

        // Name beside the cost, or the cost on its own row when the name would be squeezed.
        val besideWidth =
            (contentWidth - manaWidth - NAME_MANA_GAP).coerceAtLeast(MIN_WRAP_WIDTH)
        val fullLines = wrapLines(creature.name, namePaint, contentWidth)
        val besideLines = wrapLines(creature.name, namePaint, besideWidth)
        val manaOwnLine =
            manaWidth > 0f &&
                    (besideLines.size > fullLines.size || besideWidth < contentWidth * MIN_NAME_SHARE)

        val nameLines = (if (manaOwnLine) fullLines else besideLines).ifEmpty { listOf("") }
        val typeLines = wrapLines(creature.type, typePaint, contentWidth).ifEmpty { listOf("") }
        val extraNameHeight =
            (nameLines.size - 1) * nameLineHeight + (if (manaOwnLine) nameLineHeight else 0f)
        val extraTypeHeight = (typeLines.size - 1) * typeLineHeight

        val art = artBitmap?.let {
            val scaledHeight = (printWidth.toFloat() * (it.height.toFloat() / it.width.toFloat())).toInt()
            it.scale(printWidth, scaledHeight)
        }
        val rulesLines = wrapLines(creature.text, textPaint, contentWidth)
        val rulesHeight = if (rulesLines.isEmpty()) 0f else rulesLines.size * textLineHeight + 12f

        var totalHeight = 0f
        totalHeight += nameHeight + extraNameHeight + 7f
        if (art != null) totalHeight += art.height + 13f
        totalHeight += typeHeight + extraTypeHeight + 7f
        if (rulesHeight > 0f) totalHeight += rulesHeight + 7f
        totalHeight += ptHeight + 10f

        val out = createBitmap(printWidth, totalHeight.toInt().coerceAtLeast(120))
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)

        var y = 36f
        nameLines.forEachIndexed { i, line ->
            canvas.drawText(line, PADDING, y + i * nameLineHeight, namePaint)
        }
        if (manaWidth > 0f) {
            val manaY = if (manaOwnLine) y + nameLines.size * nameLineHeight else y
            val manaX = maxOf(PADDING, printWidth - PADDING - manaWidth)
            canvas.drawText(manaText, manaX, manaY, namePaint)
        }
        y += extraNameHeight + 8f
        canvas.drawLine(PADDING, y, printWidth - PADDING, y, rulePaint)
        y += 8f

        if (art != null) {
            canvas.drawBitmap(art, 0f, y, null)
            y += art.height + 6f
            canvas.drawLine(PADDING, y, printWidth - PADDING, y, rulePaint)
            y += 8f
        }

        typeLines.forEachIndexed { i, line ->
            canvas.drawText(line, PADDING, y + 24f + i * typeLineHeight, typePaint)
        }
        y += 30f + extraTypeHeight
        canvas.drawLine(PADDING, y, printWidth - PADDING, y, rulePaint)
        y += 8f

        if (rulesLines.isNotEmpty()) {
            for (line in rulesLines) {
                canvas.drawText(line, PADDING, y + 22f, textPaint)
                y += textLineHeight
            }
            y += 2f
            canvas.drawLine(PADDING, y, printWidth - PADDING, y, rulePaint)
            y += 8f
        }

        if (creature.power.isNotBlank() && creature.toughness.isNotBlank()) {
            val pt = "${creature.power} / ${creature.toughness}"
            val pw = ptPaint.measureText(pt)
            canvas.drawText(pt, printWidth - PADDING - pw, y + 32f, ptPaint)
        }

        return out
    }

    /** "{2}{W}{W/U}" -> "2W(W/U)": shorter, so more room is left for the card name. */
    private fun compactMana(cost: String): String =
        Regex("\\{([^}]*)}").replace(cost) { m ->
            val symbol = m.groupValues[1]
            if ('/' in symbol) "($symbol)" else symbol
        }

    private fun wrapLines(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return emptyList()
        val lines = mutableListOf<String>()
        for (paragraph in text.split("\n")) {
            val words = paragraph.split(' ')
            var current = ""
            for (word in words) {
                val test = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(test) <= maxWidth) {
                    current = test
                    continue
                }
                if (current.isNotEmpty()) lines.add(current)

                // A single word wider than the line: break it by characters.
                var rest = word
                while (paint.measureText(rest) > maxWidth && rest.length > 1) {
                    val n = paint.breakText(rest, true, maxWidth, null).coerceAtLeast(1)
                    lines.add(rest.substring(0, n))
                    rest = rest.substring(n)
                }
                current = rest
            }
            if (current.isNotEmpty()) lines.add(current)
        }
        return lines
    }
}