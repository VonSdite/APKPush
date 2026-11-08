package dev.tvapkpush

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

class UiIcon(private val kind: Kind, color: Int) : Drawable() {
    enum class Kind { DEVICES, FILE, CHEVRON, MORE, INFO, CHECK, CIRCLE, HELP, RETRY, PUSH, APPS, SEARCH, DOWNLOAD, CLOSE, PLUS }

    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = 1.7f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path().apply {
        when (kind) {
            Kind.DEVICES -> {
                moveTo(12f, 17.5f); lineTo(4f, 17.5f); quadTo(2f, 17.5f, 2f, 15.5f)
                lineTo(2f, 5.5f); quadTo(2f, 3.5f, 4f, 3.5f); lineTo(19f, 3.5f)
                quadTo(21f, 3.5f, 21f, 5.5f); lineTo(21f, 7f)
                moveTo(9f, 17.5f); lineTo(9f, 21f); moveTo(6f, 21f); lineTo(12f, 21f)
                addRoundRect(15f, 10f, 23f, 22f, 1.5f, 1.5f, Path.Direction.CW)
                moveTo(18f, 19.5f); lineTo(20f, 19.5f)
            }
            Kind.FILE -> {
                moveTo(6f, 2f); lineTo(14f, 2f); lineTo(20f, 8f); lineTo(20f, 20f)
                quadTo(20f, 22f, 18f, 22f); lineTo(6f, 22f); quadTo(4f, 22f, 4f, 20f)
                lineTo(4f, 4f); quadTo(4f, 2f, 6f, 2f)
                moveTo(14f, 2f); lineTo(14f, 8f); lineTo(20f, 8f)
                moveTo(8f, 13f); lineTo(16f, 13f); moveTo(8f, 17f); lineTo(13f, 17f)
            }
            Kind.CHEVRON -> { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) }
            Kind.INFO -> {
                addCircle(12f, 12f, 9f, Path.Direction.CW)
                moveTo(12f, 11f); lineTo(12f, 17f)
                moveTo(12f, 7.2f); lineTo(12f, 7.3f)
            }
            Kind.CHECK -> {
                addCircle(12f, 12f, 9f, Path.Direction.CW)
                moveTo(7.5f, 12f); lineTo(10.5f, 15f); lineTo(16.5f, 9f)
            }
            Kind.CIRCLE -> addCircle(12f, 12f, 9f, Path.Direction.CW)
            Kind.HELP -> {
                addCircle(12f, 12f, 9f, Path.Direction.CW)
                moveTo(9f, 8.5f); cubicTo(9f, 5.5f, 15f, 5.5f, 15f, 9f)
                cubicTo(15f, 11.5f, 12f, 11f, 12f, 14f)
                moveTo(12f, 17f); lineTo(12f, 17.1f)
            }
            Kind.RETRY -> {
                moveTo(4f, 10f); cubicTo(5f, 4f, 13f, 1.5f, 18f, 6f)
                cubicTo(23f, 10.5f, 20f, 20f, 13f, 20f)
                cubicTo(9f, 20f, 6.5f, 18f, 5.5f, 16f)
                moveTo(4f, 4f); lineTo(4f, 10f); lineTo(10f, 10f)
            }
            Kind.PUSH -> {
                moveTo(13f, 6.5f); lineTo(13f, 4f); quadTo(13f, 2f, 11f, 2f)
                lineTo(5f, 2f); quadTo(3f, 2f, 3f, 4f); lineTo(3f, 20f)
                quadTo(3f, 22f, 5f, 22f); lineTo(11f, 22f); quadTo(13f, 22f, 13f, 20f)
                lineTo(13f, 16.5f)
                moveTo(7f, 18.5f); lineTo(9f, 18.5f)
                moveTo(9f, 11.5f); lineTo(21f, 11.5f)
                moveTo(17f, 7.5f); lineTo(21f, 11.5f); lineTo(17f, 15.5f)
            }
            Kind.APPS -> {
                addRoundRect(3f, 3f, 10f, 10f, 2f, 2f, Path.Direction.CW)
                addRoundRect(14f, 3f, 21f, 10f, 2f, 2f, Path.Direction.CW)
                addRoundRect(3f, 14f, 10f, 21f, 2f, 2f, Path.Direction.CW)
                addRoundRect(14f, 14f, 21f, 21f, 2f, 2f, Path.Direction.CW)
            }
            Kind.SEARCH -> {
                addCircle(10.5f, 10.5f, 6.5f, Path.Direction.CW)
                moveTo(15.5f, 15.5f); lineTo(21f, 21f)
            }
            Kind.DOWNLOAD -> {
                moveTo(12f, 3f); lineTo(12f, 15f)
                moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
                moveTo(4f, 15f); lineTo(4f, 19f); quadTo(4f, 21f, 6f, 21f)
                lineTo(18f, 21f); quadTo(20f, 21f, 20f, 19f); lineTo(20f, 15f)
            }
            Kind.CLOSE -> { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
            Kind.PLUS -> { moveTo(5f, 12f); lineTo(19f, 12f); moveTo(12f, 5f); lineTo(12f, 19f) }
            Kind.MORE -> Unit
        }
    }

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        if (kind == Kind.MORE) {
            pen.style = Paint.Style.FILL
            canvas.drawCircle(5f, 12f, 1.6f, pen)
            canvas.drawCircle(12f, 12f, 1.6f, pen)
            canvas.drawCircle(19f, 12f, 1.6f, pen)
        } else canvas.drawPath(path, pen)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { pen.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { pen.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable 兼容接口")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
