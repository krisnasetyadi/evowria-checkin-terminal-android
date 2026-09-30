package com.evowria.printerconnector

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Native dialog styling shared by the terminal setup flows. */
object EvowriaDialog {
    data class Choice(val title: String, val description: String? = null)

    fun showForm(
        context: Context,
        eyebrow: String,
        title: String,
        message: String,
        content: View,
        primaryLabel: String,
        onPrimary: (Dialog) -> Boolean,
        secondaryLabel: String = "Batal",
        cancelable: Boolean = true,
    ): Dialog {
        val shell = createShell(context, eyebrow, title, message, cancelable)
        val dialog = shell.dialog
        val body = shell.body
        body.addView(content, matchWidth(context, top = 20))
        addActions(context, dialog, body, primaryLabel, secondaryLabel, { onPrimary(dialog) }) { dialog.dismiss() }
        return dialog.showEvowria()
    }

    fun showChoices(
        context: Context,
        eyebrow: String,
        title: String,
        message: String,
        choices: List<Choice>,
        onSelected: (Int) -> Unit,
        onCancelled: () -> Unit = {},
    ): Dialog {
        val shell = createShell(context, eyebrow, title, message, cancelable = true)
        val dialog = shell.dialog
        val body = shell.body
        val choicesContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, 16), 0, 0)
        }
        choices.forEachIndexed { index, choice ->
            choicesContainer.addView(choiceRow(context, choice).apply {
                setOnClickListener {
                    dialog.dismiss()
                    onSelected(index)
                }
            }, matchWidth(context, bottom = if (index == choices.lastIndex) 0 else 8))
        }
        body.addView(choicesContainer)
        addActions(context, dialog, body, null, "Batal", null) {
            dialog.dismiss()
            onCancelled()
        }
        return dialog.showEvowria()
    }

    fun showNotice(
        context: Context,
        eyebrow: String,
        title: String,
        message: String,
        actionLabel: String = "Mengerti",
    ): Dialog {
        val shell = createShell(context, eyebrow, title, message, cancelable = true)
        val dialog = shell.dialog
        val body = shell.body
        addActions(context, dialog, body, actionLabel, null, {
            dialog.dismiss()
            true
        }, null)
        return dialog.showEvowria()
    }

    private fun createShell(
        context: Context,
        eyebrow: String,
        title: String,
        message: String,
        cancelable: Boolean,
    ): Shell {
        val dialog = Dialog(context)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 20))
            background = roundedBackground(COLOR_SURFACE, dp(context, 24))
        }
        dialog.apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(cancelable)
            setCanceledOnTouchOutside(cancelable)
            body.addView(text(context, eyebrow.uppercase(), 12, COLOR_ACCENT, Typeface.BOLD).apply {
                letterSpacing = 0.12f
            })
            body.addView(text(context, title, 23, COLOR_INK, Typeface.BOLD), matchWidth(context, top = 8))
            body.addView(text(context, message, 14, COLOR_MUTED, Typeface.NORMAL).apply {
                setLineSpacing(dp(context, 3).toFloat(), 1f)
            }, matchWidth(context, top = 8))
            setContentView(body)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.attributes = window?.attributes?.apply { dimAmount = 0.5f }
        }
        return Shell(dialog, body)
    }

    private fun addActions(
        context: Context,
        dialog: Dialog,
        body: LinearLayout,
        primaryLabel: String?,
        secondaryLabel: String?,
        onPrimary: (() -> Boolean)?,
        onSecondary: (() -> Unit)?,
    ) {
        val actions = LinearLayout(context).apply {
            gravity = Gravity.END
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 22), 0, 0)
        }
        secondaryLabel?.let { label ->
            actions.addView(button(context, label, primary = false).apply {
                setOnClickListener { onSecondary?.invoke() }
            })
        }
        primaryLabel?.let { label ->
            actions.addView(button(context, label, primary = true).apply {
                setOnClickListener { if (onPrimary?.invoke() == true) dialog.dismiss() }
            }, wrapWidth(context, start = 8))
        }
        body.addView(actions)
    }

    private fun choiceRow(context: Context, choice: Choice): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        isFocusable = true
        foreground = selectableItemBackground(context)
        setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
        background = roundedBackground(COLOR_SOFT, dp(context, 16))
        addView(text(context, choice.title, 15, COLOR_INK, Typeface.BOLD))
        choice.description?.takeIf { it.isNotBlank() }?.let { description ->
            addView(text(context, description, 12, COLOR_MUTED, Typeface.NORMAL), matchWidth(context, top = 3))
        }
    }

    private fun button(context: Context, label: String, primary: Boolean): Button = Button(context).apply {
        text = label
        isAllCaps = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 14f
        minHeight = 0
        minimumHeight = dp(context, 44)
        setPadding(dp(context, 16), 0, dp(context, 16), 0)
        if (primary) {
            setTextColor(Color.WHITE)
            background = roundedBackground(COLOR_ACCENT, dp(context, 12))
        } else {
            setTextColor(COLOR_INK)
            background = roundedBackground(COLOR_SOFT, dp(context, 12))
        }
    }

    private fun text(context: Context, value: String, sizeSp: Int, color: Int, style: Int) = TextView(context).apply {
        text = value
        textSize = sizeSp.toFloat()
        setTextColor(color)
        typeface = Typeface.create("sans-serif", style)
    }

    private fun roundedBackground(color: Int, radiusPx: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx.toFloat()
    }

    private fun selectableItemBackground(context: Context): android.graphics.drawable.Drawable? {
        val typedArray = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val drawable = typedArray.getDrawable(0)
        typedArray.recycle()
        return drawable
    }

    private fun Dialog.showEvowria(): Dialog {
        show()
        window?.setLayout((context.resources.displayMetrics.widthPixels * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        return this
    }

    private fun matchWidth(context: Context, top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = dp(context, top)
        bottomMargin = dp(context, bottom)
    }

    private fun wrapWidth(context: Context, start: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { marginStart = dp(context, start) }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private data class Shell(val dialog: Dialog, val body: LinearLayout)
    private val COLOR_SURFACE = Color.parseColor("#FFFCF9")
    private val COLOR_SOFT = Color.parseColor("#F5EEE9")
    private val COLOR_INK = Color.parseColor("#2B211D")
    private val COLOR_MUTED = Color.parseColor("#756B65")
    private val COLOR_ACCENT = Color.parseColor("#9A472F")
}
