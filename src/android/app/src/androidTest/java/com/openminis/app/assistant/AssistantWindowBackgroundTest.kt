package com.openminis.app.assistant

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantWindowBackgroundTest {
    @Test fun clearsOldCardPixelsBeforeDrawingResizedCard() {
        val bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.RED) // previous window frame
            AssistantWindowBackground().apply { setBounds(0, 0, 12, 12) }.draw(canvas)
            canvas.drawRect(2f, 2f, 10f, 6f, Paint().apply { color = Color.BLUE })
            assertEquals(Color.BLUE, bitmap.getPixel(4, 4))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(4, 10))
        } finally { bitmap.recycle() }
    }

    @Test fun clearingIsLimitedToOwnedDrawableBounds() {
        val bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.RED)
            AssistantWindowBackground().apply { setBounds(2, 2, 10, 10) }.draw(canvas)
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(5, 5))
            assertEquals(Color.RED, bitmap.getPixel(0, 0))
            assertEquals(Color.RED, bitmap.getPixel(11, 11))
        } finally { bitmap.recycle() }
    }
}
