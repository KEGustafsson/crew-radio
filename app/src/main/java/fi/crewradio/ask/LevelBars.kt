package fi.crewradio.ask

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import fi.crewradio.R

/**
 * The level the microphone is actually hearing, while the sheet listens.
 *
 * It is not decoration. A phone in a pocket, a headset that has taken the mic, a recogniser that
 * has already stopped listening — all of them look identical to a spinner and obvious here. The
 * bars fall back on their own, so speech reads as movement and silence as a flat line.
 */
class LevelBars @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary)
    }
    private val bar = RectF()
    private val heights = FloatArray(BARS)
    private var next = 0

    /**
     * One level, 0..1, as the recogniser reports it. Values arrive about ten times a second, which
     * is why each one shifts the row along rather than redrawing every bar.
     */
    fun push(level: Float) {
        heights[next] = level.coerceIn(0f, 1f)
        next = (next + 1) % BARS
        // Everything older fades, so the row settles to a flat line when nobody is speaking.
        for (i in heights.indices) if (i != next) heights[i] *= DECAY
        invalidate()
    }

    /** Back to silence, between questions. */
    fun clear() {
        heights.fill(0f)
        next = 0
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val wanted = ((BAR_WIDTH_DP + GAP_DP) * BARS * density).toInt()
        setMeasuredDimension(
            resolveSize(wanted, widthMeasureSpec),
            resolveSize((MIN_HEIGHT_DP * density).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val barWidth = BAR_WIDTH_DP * density
        val gap = GAP_DP * density
        val radius = barWidth / 2f
        val floor = MIN_BAR_DP * density
        val full = height.toFloat()
        var x = (width - (barWidth + gap) * BARS + gap) / 2f
        for (i in heights.indices) {
            // Oldest first, so the row reads left to right like a strip chart.
            val barHeight = floor + heights[(next + i) % BARS] * (full - floor)
            bar.set(x, (full - barHeight) / 2f, x + barWidth, (full + barHeight) / 2f)
            canvas.drawRoundRect(bar, radius, radius, paint)
            x += barWidth + gap
        }
    }

    private companion object {
        const val BARS = 9
        const val BAR_WIDTH_DP = 5f
        const val GAP_DP = 4f
        const val MIN_HEIGHT_DP = 36f
        const val MIN_BAR_DP = 5f
        const val DECAY = 0.86f
    }
}
