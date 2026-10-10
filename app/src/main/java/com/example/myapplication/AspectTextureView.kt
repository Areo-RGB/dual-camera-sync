package com.example.myapplication

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView

/** TextureView that sizes its height to a 9:16 portrait aspect ratio from its width. */
class AspectTextureView(context: Context, attrs: AttributeSet?) : TextureView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, width * 16 / 9)
    }
}
