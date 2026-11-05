package com.tsrewrite.client

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import uniffi.tscore.VoiceMode
import kotlin.math.hypot

/**
 * Discord-style floating controls (setting "Floating controls"): while connected and the app is in the
 * background, a small pill with the chosen buttons (mute, deafen, push to talk, disconnect) floats over other apps,
 * fading when idle or at a fixed opacity, as set in Settings. Drag it anywhere;
 * it snaps to the nearer side. Dropping it on the × at the bottom edge hides it until the app is opened again.
 */
class FloatingControls(private val context: Context, private val session: Session, private val store: Store) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = context.getSharedPreferences("overlay", Context.MODE_PRIVATE)
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var appVisible = true
    /** Dragged onto the ×: stays hidden until the app has been in front again. */
    private var dismissed = false
    private var bubble: Pill? = null
    private var target: View? = null
    private var pttToggled = false

    private val lifecycle = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) { appVisible = true; dismissed = false; update() }
        override fun onStop(owner: LifecycleOwner) { appVisible = false; update() }
    }

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycle)
        appVisible = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        scope.launch {
            combine(store.settings, session.active, session.inputMuted, session.outputMuted, session.talking) { _, _, _, _, _ -> }
                .collect { update() }
        }
    }

    fun stop() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycle)
        scope.cancel()
        hide()
    }

    private fun update() {
        val s = store.settings.value
        val anyButton = s.floatingMic || s.floatingSound || s.floatingLeave || showsPtt(s)
        val show = s.floatingControls && anyButton && session.active.value && !appVisible && !dismissed && Settings.canDrawOverlays(context)
        if (!show) { hide(); return }
        (bubble ?: Pill().also { add(it) }).refresh()
    }

    private fun showsPtt(s: com.tsrewrite.client.Settings) = s.floatingPtt && s.voiceMode == VoiceMode.PUSH_TO_TALK

    private fun hide() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        hideTarget()
        if (pttToggled) { pttToggled = false; session.client.setPushToTalk(false) }
    }

    private fun overlayParams(w: Int, h: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun screen(): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= 30) {
        wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
    } else {
        @Suppress("DEPRECATION") android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
    }

    private fun add(p: Pill) {
        val (w, h) = screen()
        p.refresh() // its height depends on the chosen buttons
        p.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val params = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true)
        val right = prefs.getBoolean("right", true)
        params.x = if (right) w - p.measuredWidth - dp(EDGE) else dp(EDGE)
        params.y = (prefs.getFloat("y", 0.35f) * h).toInt().coerceIn(dp(48), h - p.measuredHeight - dp(16))
        p.params = params
        wm.addView(p, params)
        bubble = p
        p.alpha = 0f; p.scaleX = 0.8f; p.scaleY = 0.8f
        p.animate().alpha(p.restingAlpha()).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
        p.fadeWhenIdle()
    }

    // The close target: a round × sitting on the bottom edge, shown only while dragging.
    private fun showTarget() {
        if (target != null) return
        val size = dp(TARGET)
        val v = ImageView(context).apply {
            setImageResource(R.drawable.ic_overlay_close)
            setColorFilter(Color.WHITE)
            setPadding(dp(17), dp(17), dp(17), dp(17))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE62A2D30.toInt()); setStroke(dp(1), 0x40FFFFFF) }
            alpha = 0f
        }
        val (w, h) = screen()
        val params = overlayParams(size, size, false).apply { x = (w - size) / 2; y = h - size - dp(TARGET_MARGIN) }
        wm.addView(v, params)
        v.animate().alpha(1f).setDuration(150).start()
        target = v
    }

    private fun hideTarget() {
        target?.let { runCatching { wm.removeView(it) } }
        target = null
    }

    private fun nearTarget(p: Pill): Boolean {
        val (w, h) = screen()
        val cx = p.params.x + p.width / 2f
        val cy = p.params.y + p.height / 2f
        val tx = w / 2f
        val ty = h - dp(TARGET) / 2f - dp(TARGET_MARGIN)
        return hypot(cx - tx, cy - ty) < dp(TARGET) * 1.3f
    }

    private fun round(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /** The floating pill: the chosen ones of mic (green while you talk), headset, talk button and hang up, stacked. */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class Pill : FrameLayout(context) {
        lateinit var params: WindowManager.LayoutParams
        private val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0xEB181A1C.toInt()); setStroke(dp(1), 0x33FFFFFF) }
            elevation = dp(6).toFloat()
        }
        private val mic = button { session.toggleInputMuted() }
        private val sound = button { session.toggleOutputMuted() }
        private val ptt = button(null)
        private val leave = button { session.disconnect() }
        private var wasTalking = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0
        private var dragging = false
        private var wasNear = false
        private val buttons = listOf(mic, sound, ptt, leave)
        private val fade = Runnable { animate().alpha(FADED).setDuration(400).start() }

        init {
            clipToPadding = false
            setPadding(dp(4), dp(4), dp(4), dp(8)) // room for the shadow
            addView(column)
            buttons.forEach { column.addView(it, LinearLayout.LayoutParams(dp(BUTTON), dp(BUTTON))) }
            ptt.setOnTouchListener { v, e ->
                val s = store.settings.value
                if (session.inputMuted.value) return@setOnTouchListener true
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        if (s.pttToggle) { pttToggled = !pttToggled; session.client.setPushToTalk(pttToggled) }
                        else session.client.setPushToTalk(true)
                        v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!s.pttToggle) session.client.setPushToTalk(false)
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    }
                }
                refresh()
                true
            }
        }

        private fun button(onClick: (() -> Unit)?) = ImageView(context).apply {
            setPadding(dp(11), dp(11), dp(11), dp(11))
            setColorFilter(Color.WHITE)
            if (onClick != null) setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onClick() }
        }

        /** A fixed opacity from the settings, otherwise fully visible (until it fades). */
        fun restingAlpha(): Float {
            val s = store.settings.value
            return if (s.floatingFixedOpacity) s.floatingOpacity.coerceIn(MIN_OPACITY, 1f) else 1f
        }

        fun fadeWhenIdle() {
            removeCallbacks(fade)
            val s = store.settings.value
            if (s.floatingFade && !s.floatingFixedOpacity) postDelayed(fade, IDLE_MS)
        }

        // Any touch brings a faded pill back; the idle wait starts over when the finger lifts.
        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                removeCallbacks(fade)
                animate().alpha(restingAlpha()).setDuration(120).start()
            }
            val handled = super.dispatchTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) fadeWhenIdle()
            return handled
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(fade)
            super.onDetachedFromWindow()
        }

        private fun chooseButtons() {
            val s = store.settings.value
            mic.visibility = if (s.floatingMic) View.VISIBLE else View.GONE
            sound.visibility = if (s.floatingSound) View.VISIBLE else View.GONE
            ptt.visibility = if (showsPtt(s)) View.VISIBLE else View.GONE
            leave.visibility = if (s.floatingLeave) View.VISIBLE else View.GONE
            // A gap only between the buttons that show.
            var first = true
            for (b in buttons) {
                if (b.visibility != View.VISIBLE) continue
                val top = if (first) 0 else dp(6)
                first = false
                val lp = b.layoutParams as LinearLayout.LayoutParams
                if (lp.topMargin != top) { lp.topMargin = top; b.layoutParams = lp }
            }
        }

        fun refresh() {
            chooseButtons()
            val muted = session.inputMuted.value
            val deaf = session.outputMuted.value
            val me = session.server.value?.ownClientId
            val talking = me != null && session.talking.value.containsKey(me)
            mic.setImageResource(if (muted) R.drawable.ic_notif_mic_off else R.drawable.ic_notif_mic)
            // Like the channel tree's talk indicator: the mic lights up green while you transmit.
            val transmitting = talking && !muted
            mic.background = round(when { muted -> RED; transmitting -> GREEN; else -> SURFACE })
            if (transmitting && !wasTalking) {
                mic.animate().scaleX(1.12f).scaleY(1.12f).setDuration(90).withEndAction {
                    mic.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                }.start()
            }
            wasTalking = transmitting
            mic.contentDescription = if (muted) "Unmute microphone" else "Mute microphone"
            sound.setImageResource(if (deaf) R.drawable.ic_notif_headset_off else R.drawable.ic_notif_headset)
            sound.background = round(if (deaf) RED else SURFACE)
            sound.contentDescription = if (deaf) "Undeafen" else "Deafen"
            ptt.setImageResource(R.drawable.ic_overlay_ptt)
            ptt.background = round(when { muted -> SURFACE; talking || pttToggled -> GREEN; else -> BLUE })
            ptt.alpha = if (muted) 0.45f else 1f
            ptt.contentDescription = "Push to talk"
            leave.setImageResource(R.drawable.ic_notif_call_end)
            leave.background = round(RED_DARK)
            leave.contentDescription = "Disconnect"
        }

        // A move past the touch slop anywhere but on the talk button drags the pill instead of clicking.
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> if (!onPtt(e) && hypot(e.rawX - downX, e.rawY - downY) > slop) { dragging = true; showTarget() }
            }
            return dragging
        }

        private fun onPtt(e: MotionEvent): Boolean {
            if (ptt.visibility != View.VISIBLE) return false
            val loc = IntArray(2).also { ptt.getLocationOnScreen(it) }
            return downX >= loc[0] && downX <= loc[0] + ptt.width && downY >= loc[1] && downY <= loc[1] + ptt.height
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > slop) { dragging = true; showTarget() }
                    if (dragging) {
                        params.x = startX + (e.rawX - downX).toInt()
                        params.y = startY + (e.rawY - downY).toInt()
                        runCatching { wm.updateViewLayout(this, params) }
                        val near = nearTarget(this)
                        if (near != wasNear) {
                            wasNear = near
                            if (near) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            target?.animate()?.scaleX(if (near) 1.25f else 1f)?.scaleY(if (near) 1.25f else 1f)?.setDuration(120)?.start()
                            (target?.background as? GradientDrawable)?.setColor(if (near) RED else 0xE62A2D30.toInt())
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) drop()
                    dragging = false
                }
            }
            return true
        }

        private fun drop() {
            if (nearTarget(this)) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                dismissed = true
                animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(150).withEndAction { hide() }.start()
                return
            }
            hideTarget()
            wasNear = false
            // Snap to the nearer side, keeping the height.
            val (w, h) = screen()
            val right = params.x + width / 2 > w / 2
            val endX = if (right) w - width - dp(EDGE) else dp(EDGE)
            params.y = params.y.coerceIn(dp(48), h - height - dp(16))
            prefs.edit().putBoolean("right", right).putFloat("y", params.y.toFloat() / h).apply()
            ValueAnimator.ofInt(params.x, endX).apply {
                duration = 260
                interpolator = OvershootInterpolator(0.8f)
                addUpdateListener { params.x = it.animatedValue as Int; runCatching { wm.updateViewLayout(this@Pill, params) } }
                start()
            }
        }
    }

    private companion object {
        const val BUTTON = 44
        const val EDGE = 6
        const val TARGET = 58
        const val TARGET_MARGIN = 10
        /** How long the pill stays fully visible after a touch, and how see-through it then gets. */
        const val IDLE_MS = 4000L
        const val FADED = 0.35f
        const val MIN_OPACITY = 0.1f
        val SURFACE = 0xFF2A2D30.toInt()
        val RED = 0xFFF44336.toInt()
        val GREEN = 0xFF4CAF50.toInt()
        val BLUE = 0xFF1A91D0.toInt()
        val RED_DARK = 0xFFC62828.toInt()
    }
}
