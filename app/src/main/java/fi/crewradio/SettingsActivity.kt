package fi.crewradio

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

/**
 * The settings screen: a stock preference list backed by the default SharedPreferences,
 * so [Prefs] reads whatever is typed here. Each field is checked against [SettingsRules]
 * before it is stored; a bad value re-opens the dialog with the text still in it and says why,
 * so nothing typed is lost.
 *
 * Name and hop limit apply live (the activity pushes them into the engine on resume);
 * the network settings and the channel key take effect the next time Connect is pressed,
 * and the screen says so.
 *
 * A row the fleet's administrator has set ([Prefs.isManaged]) is shown disabled and says so.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<View>(R.id.root).padForWindowInsets()
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settings, Fragment()).commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class Fragment : PreferenceFragmentCompat() {

        /** A value the rule refused, kept until the dialog re-opens so the typing is not thrown away. */
        private val refused = HashMap<String, String>()

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            val prefs = Prefs(requireContext())

            rule(Prefs.KEY_CREW_NAME, R.string.why_crew_name) { SettingsRules.validCrewName(it) }
            rule(Prefs.KEY_NAME, R.string.why_name) { SettingsRules.validName(it) }
            rule(Prefs.KEY_GROUP, R.string.why_group) { SettingsRules.validGroup(it) }
            rule(Prefs.KEY_PORT, R.string.why_port, numeric = true) { SettingsRules.validPort(it) }
            rule(Prefs.KEY_HOPS, R.string.why_hops, numeric = true) { SettingsRules.validHops(it) }
            rule(Prefs.KEY_CHANNEL_KEY, R.string.why_channel_key) { SettingsRules.validChannelKey(it) }
            channelKey(prefs)

            // Managed configuration: the administrator's value is what the app uses, so the row
            // says so and cannot be typed over.
            for (key in MANAGED_KEYS) {
                if (!prefs.isManaged(key)) continue
                findPreference<Preference>(key)?.apply {
                    isEnabled = false
                    summary = getString(R.string.managed_by_org)
                }
            }
        }

        /**
         * The channel key row and the three actions under it. The key itself never appears in the
         * list: the summary is a mask, "Show the key" puts it on screen when the crew asks for it,
         * "Share the key" hands it to another phone, and "New random key" replaces it after a
         * confirmation, because every other phone then has to be given the new one.
         */
        private fun channelKey(prefs: Prefs) {
            val pref = findPreference<EditTextPreference>(Prefs.KEY_CHANNEL_KEY) ?: return
            // The key the app actually uses: the administrator's if the fleet sets one, else this
            // phone's own. A phone that has never joined has none yet, and reading it makes one.
            val key = prefs.channelKey
            // Never write a managed key into the phone's own store; that row is read-only anyway.
            if (!prefs.isManaged(Prefs.KEY_CHANNEL_KEY)) pref.text = key
            pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                val shown = p.text.orEmpty()
                val mask = SettingsRules.maskChannelKey(shown)
                when (SettingsRules.channelKeyState(shown)) {
                    SettingsRules.KeyState.MISSING -> getString(R.string.key_not_set)
                    SettingsRules.KeyState.SHORT -> getString(R.string.key_masked_short, mask, getString(R.string.key_short))
                    SettingsRules.KeyState.OK -> mask
                }
            }

            findPreference<Preference>(Prefs.KEY_CHANNEL_KEY_SHOW)?.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.pref_channel_key_title)
                    .setMessage(prefs.channelKey)          // read now: "New random key" may have replaced it
                    .setPositiveButton(R.string.ok, null)
                    .show()
                true
            }
            findPreference<Preference>(Prefs.KEY_CHANNEL_KEY_SHARE)?.setOnPreferenceClickListener {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.key_share_subject))
                    putExtra(Intent.EXTRA_TEXT, getString(R.string.key_share_text, prefs.channelKey))
                }
                startActivity(Intent.createChooser(send, getString(R.string.pref_key_share_title)))
                true
            }
            // A managed key belongs to the administrator: Prefs.channelKey keeps returning theirs, so
            // replacing it here would write a key the app never uses and claim a change that did not happen.
            findPreference<Preference>(Prefs.KEY_CHANNEL_KEY_NEW)?.isVisible = !prefs.isManaged(Prefs.KEY_CHANNEL_KEY)
            findPreference<Preference>(Prefs.KEY_CHANNEL_KEY_NEW)?.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.key_new_confirm_title)
                    .setMessage(R.string.key_new_confirm_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.key_new_confirm_ok) { _, _ ->
                        pref.text = SettingsRules.generateChannelKey()
                    }
                    .show()
                true
            }
        }

        /**
         * Refuses a value the rule rejects, explaining why and re-opening the dialog with the
         * refused text still in it. The keyboard is set per field: digits for the numbers, and for
         * the channel key a visible-password keyboard with no suggestions, so a key like `q7wk-…`
         * is not autocorrected or capitalised on its way in.
         */
        private fun rule(key: String, why: Int, numeric: Boolean = false, ok: (String) -> Boolean) {
            val pref = findPreference<EditTextPreference>(key) ?: return
            pref.setOnBindEditTextListener { field ->
                when {
                    numeric -> field.inputType = InputType.TYPE_CLASS_NUMBER
                    key == Prefs.KEY_CHANNEL_KEY -> field.inputType =
                        InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                refused.remove(key)?.let { field.setText(it); field.setSelection(it.length) }
            }
            pref.setOnPreferenceChangeListener { p, value ->
                val typed = value.toString()
                if (ok(typed)) return@setOnPreferenceChangeListener true
                refused[key] = typed
                Toast.makeText(requireContext(), why, Toast.LENGTH_LONG).show()
                // The dialog that just closed is still being dismissed; hand the re-open to the
                // next frame or the preference framework finds it and does nothing.
                view?.post { onDisplayPreferenceDialog(p) }
                false
            }
        }

        private companion object {
            /** The keys an EMM may set; the rest are per phone (see res/xml/app_restrictions.xml). */
            val MANAGED_KEYS = listOf(
                Prefs.KEY_CREW_NAME, Prefs.KEY_NAME, Prefs.KEY_CHANNEL_KEY, Prefs.KEY_GROUP,
                Prefs.KEY_PORT, Prefs.KEY_HOPS, Prefs.KEY_RELAY, Prefs.KEY_FULL_DUPLEX,
                Prefs.KEY_OPUS, Prefs.KEY_AUDIO_ROUTE
            )
        }
    }
}
