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
import fi.crewradio.ask.AskRecognizer
import fi.crewradio.ask.SignalKClient
import fi.crewradio.ask.SignalKDiscovery
import fi.crewradio.ask.SignalKUrl

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

        /** Looks for the boat's server while this screen is open; stopped with the screen. */
        private var discovery: SignalKDiscovery? = null

        /**
         * The address mDNS filled in, so the server row can say where it came from.
         *
         * The row's summary belongs to its SummaryProvider and to nothing else: androidx throws
         * "Preference already has a SummaryProvider set" the moment anything assigns `summary`
         * directly, which is what a discovery landing on an open Settings screen used to do.
         */
        private var discovered: String? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            val prefs = Prefs(requireContext())

            rule(Prefs.KEY_CREW_NAME, R.string.why_crew_name) { SettingsRules.validCrewName(it) }
            rule(Prefs.KEY_NAME, R.string.why_name) { SettingsRules.validName(it) }
            rule(Prefs.KEY_GROUP, R.string.why_group) { SettingsRules.validGroup(it) }
            rule(Prefs.KEY_PORT, R.string.why_port, numeric = true) { SettingsRules.validPort(it) }
            rule(Prefs.KEY_HOPS, R.string.why_hops, numeric = true) { SettingsRules.validHops(it) }
            rule(Prefs.KEY_CHANNEL_KEY, R.string.why_channel_key) { SettingsRules.validChannelKey(it) }
            rule(Prefs.KEY_ASK_SERVER, R.string.why_ask_server) { it.isBlank() || SignalKUrl.valid(it) }
            channelKey(prefs)
            ask(prefs)

            // Managed configuration: the administrator's value is what the app uses, so the row
            // says so and cannot be typed over.
            for (key in MANAGED_KEYS) {
                if (!prefs.isManaged(key)) continue
                findPreference<Preference>(key)?.apply {
                    isEnabled = false
                    // Preference.setSummary throws once a SummaryProvider is set, and the server
                    // row has one. Its provider says "set by your organisation" itself, so the
                    // row is only disabled here — assigning the summary would crash the screen.
                    if (key != Prefs.KEY_ASK_SERVER) summary = getString(R.string.managed_by_org)
                }
            }
        }

        /**
         * The "Ask the boat" rows: what the server is, and pairing with it.
         *
         * Pairing runs Signal K's own device flow — this phone asks, somebody with the admin page
         * open approves — rather than asking the crew to paste a token: nothing secret is ever
         * shown, typed or read out. The request is made on a worker thread and its state polled
         * until the server has decided; the summary says what is happening the whole time.
         */
        /**
         * The rows under the ask switch follow [Prefs.askEnabled] rather than an XML dependency on
         * it. A dependency reads the switch, and the switch is disabled whenever the fleet sets
         * the value — so a fleet that turns the feature *on* centrally would leave every row under
         * it dead. This asks what the app will actually do.
         */
        private fun askRowsEnabled(prefs: Prefs) {
            val on = prefs.askEnabled
            for (key in ASK_CHILD_KEYS) {
                val row = findPreference<Preference>(key) ?: continue
                if (prefs.isManaged(key)) continue        // already disabled, and says why
                row.isEnabled = on
            }
        }

        private fun ask(prefs: Prefs) {
            findPreference<Preference>(Prefs.KEY_ASK_SERVER)?.summaryProvider =
                Preference.SummaryProvider<Preference> {
                    val typed = prefs.askServerTyped
                    when {
                        prefs.isManaged(Prefs.KEY_ASK_SERVER) ->
                            getString(R.string.managed_by_org) + " · " + SignalKUrl.describe(typed)
                        typed.isNullOrBlank() -> getString(R.string.pref_ask_server_none)
                        typed == discovered -> getString(R.string.pref_ask_server_found, SignalKUrl.describe(typed))
                        else -> SignalKUrl.describe(typed)
                    }
                }

            // A phone that cannot recognise speech without a network says so instead of the
            // privacy note, and says it here rather than failing when the button is pressed.
            findPreference<Preference>(KEY_ASK_NOTE)?.setSummary(
                if (AskRecognizer.available(requireContext())) R.string.pref_ask_privacy
                else R.string.pref_ask_unsupported
            )

            // Nothing set: look for a server on the network and fill it in. The crew can always
            // type an address instead, and a boat network that blocks multicast still works.
            if (prefs.askServerTyped.isNullOrBlank() && !prefs.isManaged(Prefs.KEY_ASK_SERVER)) discover()

            askRowsEnabled(prefs)
            findPreference<Preference>(Prefs.KEY_ASK_ENABLED)?.setOnPreferenceChangeListener { _, value ->
                // The stored value has not been written yet, so ask the new one directly.
                val on = value == true
                for (key in ASK_CHILD_KEYS) {
                    if (prefs.isManaged(key)) continue
                    findPreference<Preference>(key)?.isEnabled = on
                }
                true
            }

            val pair = findPreference<Preference>(Prefs.KEY_ASK_PAIR) ?: return
            pair.summary = pairSummary(prefs)
            pair.setOnPreferenceClickListener {
                val base = prefs.askServer
                if (base == null) {
                    Toast.makeText(requireContext(), R.string.pref_ask_server_none, Toast.LENGTH_LONG).show()
                    return@setOnPreferenceClickListener true
                }
                pair.summary = getString(R.string.pref_ask_pair_waiting)
                startPairing(base, prefs, pair)
                true
            }
        }

        /**
         * mDNS, while this screen is open. The first server found is written into the row — it is
         * a suggestion the crew can overwrite, not a decision, and it is only made when nothing
         * was set.
         */
        private fun discover() {
            // The row is found here, on the main thread: mDNS calls back on a binder thread and
            // the preference tree is not safe to walk from one.
            val row = findPreference<EditTextPreference>(Prefs.KEY_ASK_SERVER) ?: return
            val finder = SignalKDiscovery(requireContext()).also { discovery = it }
            finder.start(
                onFound = { found ->
                    row.context.mainExecutor.execute {
                        if (!isAdded) return@execute
                        val prefs = Prefs(requireContext())
                        if (!prefs.askServerTyped.isNullOrBlank()) return@execute   // the crew got there first
                        // Before setText, which persists the address and asks the row to redraw:
                        // the SummaryProvider reads this to say the address was found, not typed.
                        discovered = found.url
                        row.text = found.url
                    }
                },
                onDone = { /* no mDNS, or discovery refused: the typed address is the way in */ },
            )
        }

        override fun onDestroyView() {
            discovery?.stop()
            discovery = null
            super.onDestroyView()
        }

        /** What the pairing row says about the token this phone holds. */
        private fun pairSummary(prefs: Prefs): String = when {
            prefs.askToken == null -> getString(R.string.pref_ask_pair_none)
            prefs.askScope == Prefs.ASK_SCOPE_WRITE -> getString(R.string.pref_ask_pair_write)
            else -> getString(R.string.pref_ask_pair_read)
        }

        /**
         * Asks the server for a token and waits for somebody to approve it, on a worker thread.
         * The fragment may go away while this runs, so every result is dropped unless it is still
         * added; nothing here touches a view off the main thread.
         */
        private fun startPairing(base: String, prefs: Prefs, row: Preference) {
            val context = requireContext().applicationContext
            val clientId = prefs.pairingClientId
            val description = context.getString(R.string.app_name) + " · " + prefs.speakerName
            Thread({
                val client = SignalKClient(base, null)
                var summary = context.getString(R.string.pref_ask_pair_none)
                var token: String? = null
                var scope = Prefs.ASK_SCOPE_NONE
                when (val requested = client.requestAccess(clientId, description)) {
                    is SignalKClient.Result.Failed ->
                        summary = context.getString(R.string.pref_ask_pair_failed, requested.detail ?: "")

                    is SignalKClient.Result.Ok -> {
                        val href = requested.value.href
                        if (href == null) {
                            summary = context.getString(R.string.pref_ask_pair_failed, "")
                        } else {
                            // Somebody has to walk to the chart table and press approve.
                            var waited = 0L
                            while (waited < PAIR_TIMEOUT_MS) {
                                if (!sleepQuietly(PAIR_POLL_MS)) break
                                waited += PAIR_POLL_MS
                                val polled = client.pollAccess(href)
                                if (polled !is SignalKClient.Result.Ok) continue
                                val access = polled.value
                                if (access.state != COMPLETED) continue
                                val issued = access.token
                                if (issued == null) {
                                    summary = context.getString(R.string.pref_ask_pair_denied)
                                } else {
                                    token = issued
                                    scope = if (writeGranted(access.permission)) Prefs.ASK_SCOPE_WRITE
                                    else Prefs.ASK_SCOPE_READ
                                    summary = context.getString(
                                        if (scope == Prefs.ASK_SCOPE_WRITE) R.string.pref_ask_pair_write
                                        else R.string.pref_ask_pair_read
                                    )
                                }
                                break
                            }
                        }
                    }
                }
                token?.let {
                    val stored = Prefs(context)
                    stored.put(Prefs.KEY_ASK_TOKEN, it)
                    stored.put(Prefs.KEY_ASK_SCOPE, scope)
                }
                val text = summary
                context.mainExecutor.execute { if (isAdded) row.summary = text }
            }, "ptt-ask-pair").start()
        }

        /** True when the permission the server granted is enough to post an announcement. */
        private fun writeGranted(permission: String?): Boolean =
            permission.equals("ADMIN", ignoreCase = true) || permission.equals("READWRITE", ignoreCase = true)

        /** Sleeps between polls; false when the thread is interrupted, which ends the wait. */
        private fun sleepQuietly(ms: Long): Boolean = try {
            Thread.sleep(ms)
            true
        } catch (_: InterruptedException) {
            false
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
            /** The read-only note under the ask rows; it has no [Prefs] key because nothing is stored. */
            const val KEY_ASK_NOTE = "ask_note"
            /** How often the pairing row asks the server whether somebody has approved yet. */
            const val PAIR_POLL_MS = 2_000L
            /** How long it waits for that: long enough to walk to the chart table. */
            const val PAIR_TIMEOUT_MS = 180_000L
            /** The state a Signal K access request reaches once the server has decided. */
            const val COMPLETED = "COMPLETED"
            /** The rows that only make sense once the feature is on. */
            val ASK_CHILD_KEYS = listOf(
                Prefs.KEY_ASK_MODE, Prefs.KEY_ASK_SERVER, Prefs.KEY_ASK_PAIR,
                Prefs.KEY_ASK_SPEED_UNIT, Prefs.KEY_ASK_DEPTH_UNIT,
            )

            /** The keys an EMM may set; the rest are per phone (see res/xml/app_restrictions.xml). */
            val MANAGED_KEYS = listOf(
                Prefs.KEY_ASK_ENABLED, Prefs.KEY_ASK_SERVER, Prefs.KEY_ASK_MODE,
                Prefs.KEY_CREW_NAME, Prefs.KEY_NAME, Prefs.KEY_CHANNEL_KEY, Prefs.KEY_GROUP,
                Prefs.KEY_PORT, Prefs.KEY_HOPS, Prefs.KEY_RELAY, Prefs.KEY_FULL_DUPLEX,
                Prefs.KEY_OPUS, Prefs.KEY_AUDIO_ROUTE
            )
        }
    }
}
