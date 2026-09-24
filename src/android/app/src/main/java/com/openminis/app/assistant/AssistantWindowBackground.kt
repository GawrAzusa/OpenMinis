package com.openminis.app.assistant

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.Drawable

/** Clears our transparent window before its children draw, including areas vacated by the IME. */
internal class AssistantWindowBackground : Drawable() {
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    override fun draw(canvas: Canvas) {
        // A transparent ColorDrawable may skip painting entirely. On the tested
        // voice-window surface that retained the previous card after an IME resize.
        // Explicitly clear only our own bounds; do not hide screenshots or draw an opaque scrim.
        canvas.drawRect(bounds, clearPaint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Required Drawable contract")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
