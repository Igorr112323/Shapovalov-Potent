package com.shapovalov.legalar

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnReferenceClickListener {
        fun onReferenceClick(reference: LegalReference, sourceText: String)
    }

    var onReferenceClick: OnReferenceClickListener? = null

    var overlayVisible: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private var scanResult: ScanResult = ScanResult.empty()

    private val cards = ArrayList<Card>()

    private var scale = 0f

    private var offsetX = 0f

    private var offsetY = 0f

    private var pressedIndex = -1

    private var pendingItem: RecognizedReference? = null

    private val cardRadius = dp(12f)

    private val cardPadding = dp(10f)

    private val accentWidth = dp(4f)

    private val cardGap = dp(4f)

    private val anchorGap = dp(10f)

    private val screenMargin = dp(10f)

    private val blockRadius = dp(3f)

    private val touchSlop = dp(6f)

    private val blockStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = ContextCompat.getColor(context, R.color.overlay_block_stroke)
    }

    private val blockFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.overlay_block_fill)
    }

    private val referenceStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = ContextCompat.getColor(context, R.color.overlay_reference_stroke)
    }

    private val referenceFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.overlay_reference_fill)
    }

    private val connectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = ContextCompat.getColor(context, R.color.overlay_connector)
        pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(5f)), 0f)
    }

    private val cardBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.overlay_card_background)
    }

    private val cardStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
        color = ContextCompat.getColor(context, R.color.overlay_card_stroke)
    }

    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.overlay_card_title)
        textSize = sp(14f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.overlay_card_body)
        textSize = sp(12f)
    }

    private val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.overlay_card_meta)
        textSize = sp(11f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun submit(result: ScanResult) {
        scanResult = result
        pressedIndex = -1
        pendingItem = null
        rebuildCards()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildCards()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!overlayVisible || scale <= 0f) {
            return
        }

        for (block in scanResult.blocks) {
            val rect = mapRect(block.box)
            canvas.drawRoundRect(rect, blockRadius, blockRadius, blockFillPaint)
            canvas.drawRoundRect(rect, blockRadius, blockRadius, blockStrokePaint)
        }

        for (item in scanResult.references) {
            val rect = mapRect(item.box)
            canvas.drawRoundRect(rect, blockRadius, blockRadius, referenceFillPaint)
            canvas.drawRoundRect(rect, blockRadius, blockRadius, referenceStrokePaint)
        }

        for (card in cards) {
            drawConnector(canvas, card)
        }

        for (index in cards.indices) {
            drawCard(canvas, cards[index], index == pressedIndex)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!overlayVisible || scale <= 0f) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val cardIndex = findCard(event.x, event.y)
                if (cardIndex >= 0) {
                    pressedIndex = cardIndex
                    pendingItem = cards[cardIndex].item
                    invalidate()
                    return true
                }
                val item = findReference(event.x, event.y)
                if (item != null) {
                    pendingItem = item
                    return true
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (pressedIndex >= 0 && findCard(event.x, event.y) != pressedIndex) {
                    pressedIndex = -1
                    pendingItem = null
                    invalidate()
                }
                return pendingItem != null
            }

            MotionEvent.ACTION_UP -> {
                val item = pendingItem
                val cardIndex = pressedIndex
                pressedIndex = -1
                pendingItem = null
                invalidate()
                if (item == null) {
                    return false
                }
                if (cardIndex < 0 || findCard(event.x, event.y) == cardIndex) {
                    onReferenceClick?.onReferenceClick(item.reference, item.sourceText)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                pendingItem = null
                invalidate()
                return true
            }
        }
        return false
    }

    private fun rebuildCards() {
        cards.clear()
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val sourceWidth = scanResult.sourceWidth
        val sourceHeight = scanResult.sourceHeight
        if (viewWidth <= 0f || viewHeight <= 0f || sourceWidth <= 0 || sourceHeight <= 0) {
            scale = 0f
            offsetX = 0f
            offsetY = 0f
            return
        }

        scale = min(viewWidth / sourceWidth.toFloat(), viewHeight / sourceHeight.toFloat())
        offsetX = (viewWidth - sourceWidth * scale) / 2f
        offsetY = (viewHeight - sourceHeight * scale) / 2f

        val cardWidth = min(dp(272f), viewWidth - screenMargin * 2f)
        if (cardWidth <= 0f) {
            return
        }

        val textLeftOffset = cardPadding + accentWidth + dp(5f)
        val innerWidth = (cardWidth - textLeftOffset - cardPadding).toInt().coerceAtLeast(1)
        val titleWidth = cardWidth - textLeftOffset - cardPadding
        val titleHeight = titlePaint.descent() - titlePaint.ascent()

        val placed = ArrayList<RectF>()
        for (item in scanResult.references) {
            val reference = item.reference
            val anchor = mapRect(item.box)

            val title = TextUtils.ellipsize(
                reference.displayName(),
                titlePaint,
                titleWidth,
                TextUtils.TruncateAt.END
            )
            val summary = buildLayout(reference.shortSummary, bodyPaint, innerWidth, SUMMARY_LINES)
            val deadline = buildLayout(
                context.getString(R.string.overlay_deadline, reference.deadline),
                metaPaint,
                innerWidth,
                META_LINES
            )
            val risk = buildLayout(
                context.getString(R.string.overlay_risk, reference.risk),
                metaPaint,
                innerWidth,
                META_LINES
            )
            val cardHeight = cardPadding * 2f + titleHeight + cardGap + summary.height +
                cardGap + deadline.height + cardGap + risk.height
            val bounds = placeCard(anchor, cardWidth, cardHeight, viewWidth, viewHeight, placed)
            cards.add(
                Card(
                    item = item,
                    reference = reference,
                    anchor = anchor,
                    bounds = bounds,
                    title = title,
                    summary = summary,
                    deadline = deadline,
                    risk = risk,
                    textLeftOffset = textLeftOffset
                )
            )
            placed.add(bounds)
        }
    }

    private fun placeCard(
        anchor: RectF,
        cardWidth: Float,
        cardHeight: Float,
        viewWidth: Float,
        viewHeight: Float,
        placed: List<RectF>
    ): RectF {
        val maxLeft = max(screenMargin, viewWidth - cardWidth - screenMargin)
        val maxTop = max(screenMargin, viewHeight - cardHeight - screenMargin)

        var left = anchor.right + anchorGap
        if (left + cardWidth > viewWidth - screenMargin) {
            left = anchor.left - anchorGap - cardWidth
        }
        if (left < screenMargin) {
            left = (viewWidth - cardWidth) / 2f
        }
        left = left.coerceIn(screenMargin, maxLeft)

        var top = anchor.centerY() - cardHeight / 2f
        top = top.coerceIn(screenMargin, maxTop)

        var bounds = RectF(left, top, left + cardWidth, top + cardHeight)
        var attempt = 0
        while (attempt < MAX_REPOSITION_ATTEMPTS && placed.any { RectF.intersects(it, bounds) }) {
            var nextTop = bounds.bottom + anchorGap
            if (nextTop + cardHeight > viewHeight - screenMargin) {
                nextTop = screenMargin
            }
            bounds = RectF(left, nextTop, left + cardWidth, nextTop + cardHeight)
            attempt++
        }
        return bounds
    }

    private fun buildLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        maxLines: Int
    ): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(dp(1.5f), 1f)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(maxLines)
            .setIncludePad(false)
            .build()
    }

    private fun drawConnector(canvas: Canvas, card: Card) {
        val anchor = card.anchor
        val bounds = card.bounds
        val startX: Float
        val startY: Float
        val endX: Float
        val endY: Float
        if (bounds.left >= anchor.right) {
            startX = anchor.right
            startY = anchor.centerY()
            endX = bounds.left
            endY = bounds.centerY()
        } else if (bounds.right <= anchor.left) {
            startX = anchor.left
            startY = anchor.centerY()
            endX = bounds.right
            endY = bounds.centerY()
        } else if (bounds.top >= anchor.bottom) {
            startX = anchor.centerX()
            startY = anchor.bottom
            endX = bounds.centerX()
            endY = bounds.top
        } else {
            startX = anchor.centerX()
            startY = anchor.top
            endX = bounds.centerX()
            endY = bounds.bottom
        }
        canvas.drawLine(startX, startY, endX, endY, connectorPaint)
    }

    private fun drawCard(canvas: Canvas, card: Card, pressed: Boolean) {
        val bounds = card.bounds
        cardStrokePaint.color = if (pressed) {
            ContextCompat.getColor(context, R.color.overlay_card_stroke_pressed)
        } else {
            ContextCompat.getColor(context, R.color.overlay_card_stroke)
        }
        canvas.drawRoundRect(bounds, cardRadius, cardRadius, cardBackgroundPaint)
        canvas.drawRoundRect(bounds, cardRadius, cardRadius, cardStrokePaint)

        val accentRect = RectF(
            bounds.left + dp(4f),
            bounds.top + dp(10f),
            bounds.left + dp(4f) + accentWidth,
            bounds.bottom - dp(10f)
        )
        accentPaint.color = riskColor(card.reference.riskLevel)
        canvas.drawRoundRect(accentRect, dp(2f), dp(2f), accentPaint)

        val textLeft = bounds.left + card.textLeftOffset
        var top = bounds.top + cardPadding

        val titleBaseline = top - titlePaint.ascent()
        canvas.drawText(card.title, 0, card.title.length, textLeft, titleBaseline, titlePaint)
        top += titlePaint.descent() - titlePaint.ascent() + cardGap

        top = drawLayout(canvas, card.summary, textLeft, top) + cardGap
        top = drawLayout(canvas, card.deadline, textLeft, top) + cardGap
        drawLayout(canvas, card.risk, textLeft, top)
    }

    private fun drawLayout(
        canvas: Canvas,
        layout: StaticLayout,
        left: Float,
        top: Float
    ): Float {
        canvas.save()
        canvas.translate(left, top)
        layout.draw(canvas)
        canvas.restore()
        return top + layout.height
    }

    private fun riskColor(riskLevel: Int): Int {
        return when (riskLevel) {
            LegalReference.RISK_LOW -> ContextCompat.getColor(context, R.color.risk_low)
            LegalReference.RISK_MEDIUM -> ContextCompat.getColor(context, R.color.risk_medium)
            else -> ContextCompat.getColor(context, R.color.risk_high)
        }
    }

    private fun findCard(x: Float, y: Float): Int {
        for (index in cards.indices.reversed()) {
            if (cards[index].bounds.contains(x, y)) {
                return index
            }
        }
        return -1
    }

    private fun findReference(x: Float, y: Float): RecognizedReference? {
        for (item in scanResult.references) {
            val rect = RectF(mapRect(item.box))
            rect.inset(-touchSlop, -touchSlop)
            if (rect.contains(x, y)) {
                return item
            }
        }
        return null
    }

    private fun mapRect(source: RectF): RectF {
        return RectF(
            offsetX + source.left * scale,
            offsetY + source.top * scale,
            offsetX + source.right * scale,
            offsetY + source.bottom * scale
        )
    }

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        )
    }

    private fun sp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value,
            resources.displayMetrics
        )
    }

    private class Card(
        val item: RecognizedReference,
        val reference: LegalReference,
        val anchor: RectF,
        val bounds: RectF,
        val title: CharSequence,
        val summary: StaticLayout,
        val deadline: StaticLayout,
        val risk: StaticLayout,
        val textLeftOffset: Float
    )

    private companion object {
        const val SUMMARY_LINES = 3
        const val META_LINES = 2
        const val MAX_REPOSITION_ATTEMPTS = 16
    }
}
