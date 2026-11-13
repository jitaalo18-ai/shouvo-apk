package com.videoeditor.app.captions

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Looks for captions and texts (the clip's `style` holds the name). */
enum class CaptionStyle(val label: String) {
    CLASSIC("Classic"),
    BOX("Box"),
    YELLOW("Yellow"),
    NEON("Neon"),
    CLEAN("Clean"),
    ;

    companion object {
        fun of(name: String): CaptionStyle = entries.firstOrNull { it.name == name } ?: CLASSIC
    }
}

/**
 * Draws a caption to a bitmap. The preview and the export both use this, so the video looks
 * exactly like the preview. Sizes are relative to the frame: [size] is the text height as a part
 * of the frame height; lines wrap at 88 % of the frame width. Bangla and English both work (the
 * system font falls back to Noto Sans Bengali).
 */
object CaptionRenderer {
    private val cache = LruCache<String, Bitmap>(24)

    fun render(text: String, style: String, size: Float, frameW: Int, frameH: Int): Bitmap {
        val key = "$style|$size|$frameW|$frameH|$text"
        cache.get(key)?.let { if (!it.isRecycled) return it }
        val bmp = draw(text.ifBlank { " " }, CaptionStyle.of(style), size, frameW, frameH)
        cache.put(key, bmp)
        return bmp
    }

    private fun draw(text: String, style: CaptionStyle, size: Float, frameW: Int, frameH: Int): Bitmap {
        val textPx = (size * frameH).coerceAtLeast(6f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textPx
            typeface = Typeface.create(Typeface.DEFAULT, if (style == CaptionStyle.CLEAN) Typeface.NORMAL else Typeface.BOLD)
            color = when (style) {
                CaptionStyle.YELLOW -> Color.rgb(255, 214, 0)
                else -> Color.WHITE
            }
        }
        val maxW = (frameW * 0.88f).roundToInt().coerceAtLeast(10)
        val desired = ceil(StaticLayout.getDesiredWidth(text, paint)).toInt()
        val layoutW = desired.coerceIn(1, maxW)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, layoutW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(true)
            .build()
        val lineW = (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: layoutW.toFloat()
        val pad = textPx * 0.35f
        val w = (max(lineW, 1f) + 2 * pad).roundToInt()
        val h = (layout.height + 2 * pad).roundToInt()
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(2), h.coerceAtLeast(2), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (style == CaptionStyle.BOX) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 0, 0, 0) }
            canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), textPx * 0.3f, textPx * 0.3f, bg)
        }
        canvas.translate((w - layoutW) / 2f, pad)
        when (style) {
            CaptionStyle.CLASSIC, CaptionStyle.YELLOW -> {
                // black outline under the letters
                val fill = paint.color
                paint.style = Paint.Style.STROKE
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = textPx * 0.16f
                paint.color = Color.BLACK
                layout.draw(canvas)
                paint.style = Paint.Style.FILL
                paint.color = fill
                layout.draw(canvas)
            }
            CaptionStyle.NEON -> {
                val glow = TextPaint(paint).apply {
                    color = Color.rgb(0, 229, 255)
                    maskFilter = BlurMaskFilter(textPx * 0.25f, BlurMaskFilter.Blur.NORMAL)
                }
                StaticLayout.Builder.obtain(text, 0, text.length, glow, layoutW)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.05f).setIncludePad(true).build()
                    .draw(canvas)
                layout.draw(canvas)
            }
            CaptionStyle.CLEAN -> {
                paint.setShadowLayer(textPx * 0.12f, 0f, textPx * 0.05f, Color.argb(170, 0, 0, 0))
                layout.draw(canvas)
            }
            CaptionStyle.BOX -> layout.draw(canvas)
        }
        return bmp
    }
}
