package io.github.ralbra.randomize_the_gathering.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import io.github.ralbra.randomize_the_gathering.model.Creature

object CardRenderer {
    private const val PADDING = 12f
    private const val NAME_MANA_GAP = 12f
    private const val MIN_WRAP_WIDTH = 60f
    private const val MIN_NAME_SHARE = 0.4f
    private const val COMPACT_MANA = false
    private const val FACE_GAP = 32

    /**
     * Prints the front face, and every other face of a multi-face card complete below it,
     * separated by a dashed line. [backArt] is the art of the second face, if it has its own.
     */
    fun renderCardBitmap(
        creature: Creature,
        artBitmap: Bitmap?,
        printWidth: Int = 576,
        backArt: Bitmap? = null,
    ): Bitmap {
        val front = renderFace(
            name = creature.displayName,
            manaCost = creature.manaCost,
            type = creature.type,
            power = creature.power,
            toughness = creature.toughness,
            text = creature.text,
            artBitmap = artBitmap,
            printWidth = printWidth,
            footer = creature.footer,
        )
        if (creature.extraFaces.isEmpty()) return front

        val parts = mutableListOf(front)
        creature.extraFaces.forEachIndexed { index, face ->
            val art = if (index == 0 && creature.backHasOwnArt) backArt else null
            parts.add(
                renderFace(
                    name = face.name,
                    manaCost = face.manaCost,
                    type = face.type,
                    power = face.power,
                    toughness = face.toughness,
                    text = face.text,
                    artBitmap = art,
                    printWidth = printWidth,
                )
            )
        }
        return stack(parts, printWidth)
    }

    /** Puts the faces below each other with a dashed cut line between them. */
    private fun stack(parts: List<Bitmap>, printWidth: Int): Bitmap {
        val total = parts.sumOf { it.height } + FACE_GAP * (parts.size - 1)
        val out = createBitmap(printWidth, total.coerceAtLeast(1))
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)

        val dashed = Paint().apply {
            color = Color.BLACK
            strokeWidth = 3f
            pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
        }

        var y = 0
        parts.forEachIndexed { index, part ->
            if (index > 0) {
                val middle = y + FACE_GAP / 2f
                canvas.drawLine(PADDING, middle, printWidth - PADDING, middle, dashed)
                y += FACE_GAP
            }
            canvas.drawBitmap(part, 0f, y.toFloat(), null)
            y += part.height
        }
        return out
    }

    private fun renderFace(
        name: String,
        manaCost: String,
        type: String,
        power: String,
        toughness: String,
        text: String,
        artBitmap: Bitmap?,
        printWidth: Int,
        footer: String = "",
    ): Bitmap {
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
        val hasPt = power.isNotBlank() && toughness.isNotBlank()
        val hasFooter = footer.isNotBlank()
        // The footer shares the bottom row with power / toughness.
        val ptHeight = if (hasPt || hasFooter) 42f else 0f

        val manaText = if (COMPACT_MANA) compactMana(manaCost) else manaCost
        val manaWidth = if (manaText.isNotBlank()) namePaint.measureText(manaText) else 0f

        // Name beside the cost, or the cost on its own row when the name would be squeezed.
        val besideWidth =
            (contentWidth - manaWidth - NAME_MANA_GAP).coerceAtLeast(MIN_WRAP_WIDTH)
        val fullLines = wrapLines(name, namePaint, contentWidth)
        val besideLines = wrapLines(name, namePaint, besideWidth)
        val manaOwnLine =
            manaWidth > 0f &&
                    (besideLines.size > fullLines.size || besideWidth < contentWidth * MIN_NAME_SHARE)

        val nameLines = (if (manaOwnLine) fullLines else besideLines).ifEmpty { listOf("") }
        val typeLines = wrapLines(type, typePaint, contentWidth).ifEmpty { listOf("") }
        val extraNameHeight =
            (nameLines.size - 1) * nameLineHeight + (if (manaOwnLine) nameLineHeight else 0f)
        val extraTypeHeight = (typeLines.size - 1) * typeLineHeight

        val art = artBitmap?.let {
            val scaledHeight = (printWidth.toFloat() * (it.height.toFloat() / it.width.toFloat())).toInt()
            it.scale(printWidth, scaledHeight)
        }
        val rulesLines = wrapLines(text, textPaint, contentWidth)
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

        if (hasFooter) {
            canvas.drawText(footer, PADDING, y + 30f, typePaint)
        }
        if (hasPt) {
            val pt = "$power / $toughness"
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