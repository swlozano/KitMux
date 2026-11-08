package com.kitsudo.kitmux.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** FrameLayout tan alto como ancho (portada de carpeta en la cuadrícula). */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
