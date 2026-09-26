package dev.landscapify.app

import android.accessibilityservice.AccessibilityService
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.lang.ref.WeakReference

/** The window exists only during a user-started session. Android removes it if this process dies. */
class OrientationService : AccessibilityService() {
    private var overlay: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
    }

    private fun setLandscape(enabled: Boolean) {
        val windows = getSystemService(WindowManager::class.java)
        if (!enabled) {
            val current = overlay
            overlay = null
            current?.let(windows::removeView)
            return
        }
        if (overlay != null) return
        val params = WindowManager.LayoutParams(1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        overlay = View(this).also { windows.addView(it, params) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        runCatching { setLandscape(false) }
        if (instance?.get() === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: WeakReference<OrientationService>? = null
        private val main = Handler(Looper.getMainLooper())

        fun isConnected(): Boolean = instance?.get() != null

        fun setLandscape(enabled: Boolean) {
            val done = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            main.post {
                try {
                    val service = instance?.get() ?: error("Enable Landscapify in Accessibility settings.")
                    service.setLandscape(enabled)
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    done.countDown()
                }
            }
            check(done.await(5, TimeUnit.SECONDS)) { "Orientation service did not respond." }
            failure.get()?.let { throw IllegalStateException("Could not apply landscape: ${it.message}", it) }
        }
    }
}
