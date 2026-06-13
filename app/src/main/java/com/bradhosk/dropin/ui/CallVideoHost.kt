package com.bradhosk.dropin.ui

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.SurfaceViewRenderer

/**
 * Owns a single pair of [SurfaceViewRenderer] instances for the activity lifetime.
 * Surfaces survive Compose layout changes (fullscreen, call start/end, timer ticks).
 */
class CallVideoHost(
    context: Context,
    private val onBothSurfacesReady: (local: SurfaceViewRenderer, remote: SurfaceViewRenderer) -> Unit,
) {
    val localView: SurfaceViewRenderer = SurfaceViewRenderer(context)
    val remoteView: SurfaceViewRenderer = SurfaceViewRenderer(context)

    private var localAttached = false
    private var remoteAttached = false
    private var surfacesNotified = false

    init {
        localView.addOnAttachStateChangeListener(attachListener { localAttached = true })
        remoteView.addOnAttachStateChangeListener(attachListener { remoteAttached = true })
    }

    @Composable
    fun Local(modifier: Modifier) {
        AndroidView(modifier = modifier, factory = { localView })
    }

    @Composable
    fun Remote(modifier: Modifier) {
        AndroidView(modifier = modifier, factory = { remoteView })
    }

    fun release() {
        localView.release()
        remoteView.release()
        surfacesNotified = false
        localAttached = false
        remoteAttached = false
    }

    private fun attachListener(onAttached: () -> Unit) =
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                onAttached()
                notifyIfBothReady()
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        }

    private fun notifyIfBothReady() {
        if (surfacesNotified) return
        if (!localAttached || !remoteAttached) return
        surfacesNotified = true
        onBothSurfacesReady(localView, remoteView)
    }
}
