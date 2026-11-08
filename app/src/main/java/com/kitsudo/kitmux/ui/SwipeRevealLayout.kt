package com.kitsudo.kitmux.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.customview.widget.ViewDragHelper

/**
 * Fila que se desliza de derecha a izquierda para mostrar acciones ocultas.
 * Hijo 0 = panel trasero (acciones, alineado a la derecha; su ancho es lo que se revela).
 * Hijo 1 = contenido delantero (debe tener fondo opaco).
 */
class SwipeRevealLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** Se llama cuando el usuario abre o cierra la fila con el gesto. */
    var onOpenChanged: ((open: Boolean) -> Unit)? = null

    var isOpen = false
        private set

    private lateinit var back: View
    private lateinit var front: View
    private var offset = 0 // posición izquierda actual de la capa delantera (≤ 0)
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4f

    private val revealWidth: Int get() = back.width

    private val dragHelper = ViewDragHelper.create(this, 1f, object : ViewDragHelper.Callback() {
        override fun tryCaptureView(child: View, pointerId: Int) = child === front

        override fun getViewHorizontalDragRange(child: View) = revealWidth

        override fun clampViewPositionHorizontal(child: View, left: Int, dx: Int) =
            left.coerceIn(-revealWidth, 0)

        override fun onViewPositionChanged(changedView: View, left: Int, top: Int, dx: Int, dy: Int) {
            offset = left
        }

        override fun onViewDragStateChanged(state: Int) {
            if (state == ViewDragHelper.STATE_DRAGGING) parent?.requestDisallowInterceptTouchEvent(true)
        }

        override fun onViewReleased(releasedChild: View, xvel: Float, yvel: Float) {
            val open = when {
                xvel < -minFlingVelocity -> true
                xvel > minFlingVelocity -> false
                else -> offset < -revealWidth / 2
            }
            settle(open, notify = true)
        }
    })

    override fun onFinishInflate() {
        super.onFinishInflate()
        check(childCount == 2) { "SwipeRevealLayout necesita 2 hijos: panel trasero y contenido" }
        back = getChildAt(0)
        front = getChildAt(1)
    }

    /** Abre o cierra la fila desde código (p. ej. al reciclarla o al abrir otra). */
    fun setOpen(open: Boolean, animate: Boolean) {
        if (animate && isLaidOut) {
            settle(open, notify = false)
        } else {
            isOpen = open
            offset = if (open) -revealWidth else 0
            requestLayout()
        }
    }

    private fun settle(open: Boolean, notify: Boolean) {
        val changed = open != isOpen
        isOpen = open
        if (dragHelper.smoothSlideViewTo(front, if (open) -revealWidth else 0, front.top)) {
            postInvalidateOnAnimation()
        }
        if (notify && changed) onOpenChanged?.invoke(open)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (dragHelper.viewDragState == ViewDragHelper.STATE_IDLE) offset = if (isOpen) -revealWidth else 0
        front.offsetLeftAndRight(offset)
    }

    override fun computeScroll() {
        if (dragHelper.continueSettling(true)) postInvalidateOnAnimation()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = dragHelper.shouldInterceptTouchEvent(ev)

    @Suppress("ClickableViewAccessibility") // los clics los gestiona la capa delantera
    override fun onTouchEvent(event: MotionEvent): Boolean {
        dragHelper.processTouchEvent(event)
        return true
    }
}
