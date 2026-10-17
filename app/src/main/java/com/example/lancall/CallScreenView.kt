package com.example.lancall

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class CallScreenView(context: Context) : FrameLayout(context) {

    enum class Mode { INCOMING, OUTGOING, ACTIVE }

    var onAccept: (() -> Unit)? = null
    var onDecline: (() -> Unit)? = null
    var onEnd: (() -> Unit)? = null
    var onSpeakerToggle: ((Boolean) -> Unit)? = null

    private var speakerOn = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun circle(colorHex: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(colorHex))
    }

    private val avatar = TextView(context).apply {
        textSize = 44f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = circle("#475569")
        layoutParams = LinearLayout.LayoutParams(dp(120), dp(120))
    }

    private val nameTv = TextView(context).apply {
        textSize = 28f
        setTextColor(Color.parseColor("#F8FAFC"))
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(24), dp(24), 0)
    }

    private val statusTv = TextView(context).apply {
        textSize = 16f
        setTextColor(Color.parseColor("#94A3B8"))
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(8), dp(24), 0)
    }

    private fun action(
        glyph: String,
        label: String,
        colorHex: String,
        onClick: () -> Unit
    ): Pair<LinearLayout, TextView> {
        val button = TextView(context).apply {
            text = glyph
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = circle(colorHex)
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
        }
        val caption = TextView(context).apply {
            text = label
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(button)
            addView(caption)
            setOnClickListener { onClick() }
        }
        return box to button
    }

    private val declineAction = action("✕", "Decline", "#334155") { onDecline?.invoke() }
    private val speakerAction = action("🔊", "Speaker", "#334155") { toggleSpeaker() }
    private val endAction = action("✕", "End", "#334155") { onEnd?.invoke() }
    private val acceptAction = action("📞", "Accept", "#475569") { onAccept?.invoke() }

    init {
        setBackgroundColor(Color.parseColor("#0F172A"))
        isClickable = true
        isFocusable = true
        visibility = GONE

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(avatar)
            addView(nameTv)
            addView(statusTv)
        }
        addView(
            top,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply { topMargin = dp(110) }
        )

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(declineAction.first)
            addView(speakerAction.first)
            addView(endAction.first)
            addView(acceptAction.first)
        }
        addView(
            bottom,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                bottomMargin = dp(72)
                leftMargin = dp(24)
                rightMargin = dp(24)
            }
        )
    }

    val isShowing: Boolean get() = visibility == VISIBLE

    fun showIncoming(name: String) = show(Mode.INCOMING, name, "Incoming voice call")
    fun showOutgoing(name: String) = show(Mode.OUTGOING, name, "Calling…")
    fun showActive(name: String) = show(Mode.ACTIVE, name, "Connected • 00:00")

    fun setStatus(text: String) {
        statusTv.text = text
    }

    fun hide() {
        visibility = GONE
        setSpeaker(false)
    }

    fun setSpeaker(on: Boolean) {
        speakerOn = on
        speakerAction.second.background = circle(if (on) "#64748B" else "#334155")
    }

    private fun toggleSpeaker() {
        setSpeaker(!speakerOn)
        onSpeakerToggle?.invoke(speakerOn)
    }

    private fun show(mode: Mode, name: String, status: String) {
        avatar.text = name.firstOrNull()?.uppercase() ?: "?"
        nameTv.text = name
        statusTv.text = status

        val incoming = mode == Mode.INCOMING
        declineAction.first.visibility = if (incoming) VISIBLE else GONE
        acceptAction.first.visibility = if (incoming) VISIBLE else GONE
        endAction.first.visibility = if (incoming) GONE else VISIBLE
        speakerAction.first.visibility = if (mode == Mode.ACTIVE) VISIBLE else GONE

        visibility = VISIBLE
        bringToFront()
    }
}