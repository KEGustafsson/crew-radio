package fi.crewradio.ask

import android.app.Activity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import fi.crewradio.Prefs
import fi.crewradio.R

/**
 * The sheet the ask row opens: one layout, four states.
 *
 * A sheet rather than a screen, so the channel state stays visible behind it and one tap outside
 * abandons the question. Whatever closes it — the button, a tap outside, the activity going away
 * — ends the question through [AskController.finish], which is the single place the microphone
 * and the engine's voice keying are handed back.
 */
class AskSheet(
    private val activity: Activity,
    private val controller: AskController,
) {

    private val dialog = BottomSheetDialog(activity)
    private val view: View = activity.layoutInflater.inflate(R.layout.sheet_ask, null)

    private val state: TextView = view.findViewById(R.id.askState)
    private val level: LevelBars = view.findViewById(R.id.askLevel)
    private val spinner: ProgressBar = view.findViewById(R.id.askSpinner)
    private val heard: TextView = view.findViewById(R.id.askHeard)
    private val answer: TextView = view.findViewById(R.id.askAnswer)
    private val detail: TextView = view.findViewById(R.id.askDetail)
    private val typed: EditText = view.findViewById(R.id.askTyped)
    private val mode: TextView = view.findViewById(R.id.askMode)
    private val action: MaterialButton = view.findViewById(R.id.askAction)

    /** True once the question is over, so the button reads Done and does not cancel anything. */
    private var settled = false

    init {
        dialog.setContentView(view)
        dialog.setOnDismissListener { controller.finish() }
        action.setOnClickListener { dialog.dismiss() }
        mode.setOnClickListener {
            controller.toggleMode()
            showMode()
        }
        typed.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                controller.submit(typed.text.toString())
                true
            } else {
                false
            }
        }
    }

    /** Opens the sheet and starts a question. [typedOnly] skips the microphone. */
    fun show(typedOnly: Boolean = false) {
        settled = false
        showMode()
        controller.start(typedOnly, ::render) { level.push(it) }
        dialog.show()
    }

    fun dismiss() = dialog.dismiss()

    private fun showMode() {
        mode.setText(
            if (controller.mode == AskController.Mode.CREW) R.string.ask_mode_crew else R.string.ask_mode_just_me
        )
    }

    private fun render(next: AskController.State) {
        when (next) {
            is AskController.State.Listening -> {
                state.setText(R.string.ask_listening)
                panels(level = true, spinner = false, typing = false)
                heard.text = next.heard
                answer.visibility = View.GONE
                detail.visibility = View.GONE
            }

            is AskController.State.Typing -> {
                state.setText(R.string.ask_heard)
                panels(level = false, spinner = false, typing = true)
                heard.text = ""
                answer.visibility = View.GONE
                detail.visibility = View.GONE
                typed.requestFocus()
            }

            is AskController.State.Working -> {
                state.setText(R.string.ask_working)
                panels(level = false, spinner = true, typing = false)
                heard.text = next.heard
                answer.visibility = View.GONE
                detail.visibility = View.GONE
            }

            is AskController.State.Answered -> {
                settle(next.heard)
                answer.visibility = View.VISIBLE
                answer.text = next.sentence
                answer.setTextColor(activity.getColor(R.color.primary))
                detail.visibility = if (next.detail.isEmpty()) View.GONE else View.VISIBLE
                detail.text = next.detail
            }

            is AskController.State.Failed -> {
                settle(next.heard)
                answer.visibility = View.VISIBLE
                answer.text = next.message
                answer.setTextColor(activity.getColor(R.color.error))
                detail.visibility = View.GONE
            }
        }
    }

    /** The question is over: the button becomes Done, and the level meter stops implying it is listening. */
    private fun settle(said: String) {
        settled = true
        state.text = activity.getString(R.string.ask_heard)
        panels(level = false, spinner = false, typing = false)
        heard.text = said
        level.clear()
        action.setText(R.string.ask_done)
    }

    /** Which of the three middle panels is showing, and what the button says. */
    private fun panels(level: Boolean, spinner: Boolean, typing: Boolean) {
        this.level.visibility = if (level) View.VISIBLE else View.GONE
        this.spinner.visibility = if (spinner) View.VISIBLE else View.GONE
        this.typed.visibility = if (typing) View.VISIBLE else View.GONE
        action.setText(if (settled) R.string.ask_done else R.string.ask_cancel)
    }

    companion object {
        /**
         * Opens a question from the main screen, or explains why it cannot. The row itself is only
         * shown when [AskController.offered] is true, so the reasons left here are the ones that
         * can change between opening the screen and pressing it.
         */
        fun open(activity: Activity, prefs: Prefs, controller: AskController): AskSheet? {
            if (prefs.askServer == null) return null
            val sheet = AskSheet(activity, controller)
            sheet.show(typedOnly = !controller.canListen())
            return sheet
        }
    }
}
