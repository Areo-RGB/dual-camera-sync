package com.example.myapplication

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.TextureView

/** TextureView that sizes its height from its width: 9:16 in portrait, 16:9 in landscape. */
class AspectTextureView(context: Context, attrs: AttributeSet?) : TextureView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        setMeasuredDimension(width, if (landscape) width * 9 / 16 else width * 16 / 9)
    }
}
