package fi.crewradio

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import fi.crewradio.ask.AskUnits
import fi.crewradio.ask.SignalKUrl
import fi.crewradio.audio.AudioConfig

/**
 * What a setting may be. Pure Kotlin so the rules are unit-tested; [SettingsActivity]
 * uses them to refuse bad input and [Prefs] to fall back to a default if a bad value
 * ever reaches storage anyway.
 */
object SettingsRules {
    const val DEFAULT_GROUP = "239.255.42.1"
    const val DEFAULT_PORT = 47474
    /** Characters a generated channel key is made of: no 0/O, 1/l/I, so it survives being read out loud. */
    const val KEY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
    const val DEFAULT_HOPS = AudioConfig.DEFAULT_TTL

    /** Shortest key a phone still accepts from storage, so a crew that set one years ago keeps working. */
    const val KEY_MIN = 8
    /** Shortest key the settings screen accepts for a new one: the packet key is only as good as this. */
    const val NEW_KEY_MIN = 12
    const val KEY_MAX = 64

    /** Empty means "use the device name"; otherwise it has to fit a [Hello] and stay on one line. */
    fun validName(s: String): Boolean =
        s.trim().toByteArray(Charsets.UTF_8).size <= Hello.MAX_NAME_BYTES && s.none { it == '\r' || it == '\n' }

    /** An IPv4 multicast address: dotted quad, first octet 224–239. */
    fun validGroup(s: String): Boolean {
        val parts = s.trim().split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        return octets.all { it in 0..255 } && octets[0] in 224..239
    }

    /** An unprivileged port. */
    fun validPort(s: String): Boolean = s.trim().toIntOrNull()?.let { it in 1024..65535 } == true

    /**
     * A channel key this phone will use. Printable ASCII only (it is read out loud and typed on
     * other phones) and at least [KEY_MIN] characters. Aware's own 8–63 passphrase rule no longer
     * binds it: the Aware passphrase is derived from the packet key ([ChannelCrypto.awarePassphrase]).
     */
    fun validPassphrase(s: String): Boolean =
        s.length in KEY_MIN..KEY_MAX && s.all { it.code in 0x20..0x7E }

    /**
     * A channel key typed into Settings. Stricter than [validPassphrase]: a new key has to be at
     * least [NEW_KEY_MIN] characters, because the packet key is stretched from it and nothing else.
     */
    fun validChannelKey(s: String): Boolean =
        s.length in NEW_KEY_MIN..KEY_MAX && s.all { it.code in 0x20..0x7E }

    /** How good the stored key is: nothing set, an old short one, or one this build would accept. */
    enum class KeyState { MISSING, SHORT, OK }

    fun channelKeyState(s: String): KeyState = when {
        !validPassphrase(s) -> KeyState.MISSING
        !validChannelKey(s) -> KeyState.SHORT
        else -> KeyState.OK
    }

    /**
     * What the settings row shows instead of the key: enough to tell two phones apart when the
     * crew compares them, not enough for a shoulder to copy. Empty for a key that is not set.
     */
    fun maskChannelKey(s: String): String =
        if (s.length < 4) "" else "•••• •••• " + s.takeLast(4)

    /** A fresh random channel key, `xxxx-xxxx-xxxx` from [KEY_ALPHABET]: 14 characters, ~59 bits, readable aloud. */
    fun generateChannelKey(random: java.util.Random = java.security.SecureRandom()): String =
        (1..3).joinToString("-") { (1..4).map { KEY_ALPHABET[random.nextInt(KEY_ALPHABET.length)] }.joinToString("") }

    /** The header name: short enough to stay on one line at 30 sp. Empty means the app name. */
    fun validCrewName(s: String): Boolean = s.trim().length <= 24 && s.none { it == '\r' || it == '\n' }

    /** Relays a packet may cross; 1 means no relaying at all. */
    fun validHops(s: String): Boolean = s.trim().toIntOrNull()?.let { it in 1..16 } == true
}

/**
 * Typed access to the app's SharedPreferences: the settings screen's values, with
 * defaults, plus the main screen's last choices so the crew can open the app and
 * press Connect. The settings screen writes the same file through the preference
 * framework, which is why the keys live here.
 *
 * Managed configuration comes first. An EMM can push any of the keys in
 * `res/xml/app_restrictions.xml`; a restriction that is present and passes [SettingsRules] wins
 * over whatever is stored on the phone, and [isManaged] tells the settings screen to show that
 * row disabled. The bundle is read when this object is constructed, so a new [Prefs] picks up a
 * change; [PttService] listens for `ACTION_APPLICATION_RESTRICTIONS_CHANGED` and re-reads.
 */
class Prefs(context: Context) {
    private val sp = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private val appName = context.getString(R.string.app_name)
    private val managed: Bundle? =
        (context.getSystemService(Context.RESTRICTIONS_SERVICE) as? RestrictionsManager)
            ?.applicationRestrictions?.takeIf { !it.isEmpty }

    /** True when the fleet's administrator set this key and the value is usable; the row is then read-only. */
    fun isManaged(key: String): Boolean = when (key) {
        KEY_CREW_NAME -> managedString(key)?.let { SettingsRules.validCrewName(it) }
        KEY_NAME -> managedString(key)?.let { SettingsRules.validName(it) }
        KEY_CHANNEL_KEY -> managedString(key)?.let { SettingsRules.validPassphrase(it) }
        KEY_GROUP -> managedString(key)?.let { SettingsRules.validGroup(it) }
        KEY_PORT -> managedInt(key)?.let { SettingsRules.validPort(it.toString()) }
        KEY_HOPS -> managedInt(key)?.let { SettingsRules.validHops(it.toString()) }
        KEY_AUDIO_ROUTE -> managedString(key)?.let { it == ROUTE_AUTO || it == ROUTE_SPEAKER || it == ROUTE_EARPIECE }
        KEY_RELAY, KEY_FULL_DUPLEX, KEY_OPUS, KEY_ASK_ENABLED -> managedBool(key) != null
        KEY_ASK_SERVER -> managedString(key)?.let { SignalKUrl.valid(it) }
        KEY_ASK_MODE -> managedString(key)?.let { it == ASK_MODE_JUST_ME || it == ASK_MODE_CREW }
        else -> false
    } == true

    /** A managed string, trimmed, or null when the fleet does not set it. */
    private fun managedString(key: String): String? = managed?.getString(key)?.trim()?.takeIf { it.isNotEmpty() }
    private fun managedInt(key: String): Int? = managed?.takeIf { it.containsKey(key) }?.getInt(key)
    private fun managedBool(key: String): Boolean? = managed?.takeIf { it.containsKey(key) }?.getBoolean(key)

    /** What the header shows: the crew or boat name, else the app name. */
    val crewName: String
        get() = managedString(KEY_CREW_NAME)?.takeIf { SettingsRules.validCrewName(it) }
            ?: sp.getString(KEY_CREW_NAME, null)?.trim()?.takeIf { it.isNotEmpty() && SettingsRules.validCrewName(it) }
            ?: appName

    /** The name to announce, or null to use the device name. */
    val name: String?
        get() = managedString(KEY_NAME)?.takeIf { SettingsRules.validName(it) }
            ?: sp.getString(KEY_NAME, null)?.trim()?.takeIf { it.isNotEmpty() && SettingsRules.validName(it) }

    val group: String
        get() = managedString(KEY_GROUP)?.takeIf { SettingsRules.validGroup(it) }
            ?: sp.getString(KEY_GROUP, null)?.trim()?.takeIf { SettingsRules.validGroup(it) }
            ?: SettingsRules.DEFAULT_GROUP

    val port: Int
        get() = managedInt(KEY_PORT)?.takeIf { SettingsRules.validPort(it.toString()) }
            ?: sp.getString(KEY_PORT, null)?.trim()?.takeIf { SettingsRules.validPort(it) }?.toInt()
            ?: SettingsRules.DEFAULT_PORT

    /**
     * The crew's channel key: the packet encryption key, and the seed of the Wi-Fi Aware secrets.
     * Never a shared default: a phone without one generates a random key on first use (secure by
     * default) and the crew copies it to the other phones. An old install's Aware passphrase, if it
     * was changed from the former default, carries over so an existing crew keeps working. A
     * managed key wins and is never written back to the phone's own storage.
     */
    val channelKey: String
        get() {
            managedString(KEY_CHANNEL_KEY)?.takeIf { SettingsRules.validPassphrase(it) }?.let { return it }
            sp.getString(KEY_CHANNEL_KEY, null)?.takeIf { SettingsRules.validPassphrase(it) }?.let { return it }
            val legacy = sp.getString(KEY_LEGACY_PASSPHRASE, null)?.takeIf { SettingsRules.validPassphrase(it) && it != "crew-radio" }
            val key = legacy ?: SettingsRules.generateChannelKey()
            sp.edit { putString(KEY_CHANNEL_KEY, key) }
            return key
        }

    val hops: Int
        get() = managedInt(KEY_HOPS)?.takeIf { SettingsRules.validHops(it.toString()) }
            ?: sp.getString(KEY_HOPS, null)?.trim()?.takeIf { SettingsRules.validHops(it) }?.toInt()
            ?: SettingsRules.DEFAULT_HOPS

    val fullDuplex: Boolean get() = managedBool(KEY_FULL_DUPLEX) ?: sp.getBoolean(KEY_FULL_DUPLEX, false)
    /** Which physical buttons key the mic while on channel: off, headset, volume or both. */
    val hwButton: String get() = sp.getString(KEY_HW_BUTTON, HW_BOTH) ?: HW_BOTH
    val keepScreenOn: Boolean get() = sp.getBoolean(KEY_KEEP_SCREEN_ON, true)
    /** Where the voice goes: auto (headset, else loudspeaker), the loudspeaker, or the earpiece. */
    val audioRoute: String
        get() = (managedString(KEY_AUDIO_ROUTE) ?: sp.getString(KEY_AUDIO_ROUTE, ROUTE_AUTO))
            ?.takeIf { it == ROUTE_SPEAKER || it == ROUTE_EARPIECE } ?: ROUTE_AUTO
    /** Register the session as a call while a Bluetooth headset is in use; for headsets whose button hangs up. */
    val headsetAsCall: Boolean get() = sp.getBoolean(KEY_HEADSET_CALL, false)
    /** With a Bluetooth headset, speech keys the mic (VOX). */
    val headsetVox: Boolean get() = sp.getBoolean(KEY_HEADSET_VOX, false)
    /** A tone in the ear when a talk key keys or un-keys the mic. */
    val cueTones: Boolean get() = sp.getBoolean(KEY_CUE_TONES, false)
    /** Read the proximity sensor: earpiece and voice keying at the ear, screen dark meanwhile. Off: the phone acts as one without the sensor. */
    val proximitySensor: Boolean get() = sp.getBoolean(KEY_PROXIMITY, true)
    val relay: Boolean get() = managedBool(KEY_RELAY) ?: sp.getBoolean(KEY_RELAY, true)
    val opus: Boolean get() = managedBool(KEY_OPUS) ?: sp.getBoolean(KEY_OPUS, true)

    // ---- Ask the boat (Signal K) --------------------------------------------------

    /** Off until the crew turns it on: a boat without Signal K should never see the row. */
    val askEnabled: Boolean get() = managedBool(KEY_ASK_ENABLED) ?: sp.getBoolean(KEY_ASK_ENABLED, false)

    /** The boat's server as a base URL, or null when nothing usable is set. */
    val askServer: String?
        get() = SignalKUrl.normalise(managedString(KEY_ASK_SERVER) ?: sp.getString(KEY_ASK_SERVER, null))

    /** What the crew typed, for the settings row to show back to them. */
    val askServerTyped: String?
        get() = (managedString(KEY_ASK_SERVER) ?: sp.getString(KEY_ASK_SERVER, null))?.takeIf { it.isNotBlank() }

    /**
     * The token the server issued to this phone. Excluded from cloud backup and device transfer
     * along with the channel key (`res/xml/data_extraction_rules.xml`): it is a credential for
     * the boat, and it should not follow a phone that is sold or restored somewhere else.
     */
    val askToken: String? get() = sp.getString(KEY_ASK_TOKEN, null)?.takeIf { it.isNotBlank() }

    /** What that token is good for: [ASK_SCOPE_NONE], [ASK_SCOPE_READ] or [ASK_SCOPE_WRITE]. */
    val askScope: String get() = sp.getString(KEY_ASK_SCOPE, ASK_SCOPE_NONE) ?: ASK_SCOPE_NONE

    /**
     * Who hears an answer. The default is the one that cannot disturb anybody and cannot be
     * talked into transmitting by a recognition misfire.
     */
    val askMode: String
        get() = (managedString(KEY_ASK_MODE) ?: sp.getString(KEY_ASK_MODE, ASK_MODE_JUST_ME))
            ?.takeIf { it == ASK_MODE_CREW } ?: ASK_MODE_JUST_ME

    /** Knots or metres, as this crew reads them. */
    val askUnits: AskUnits.Prefs
        get() = AskUnits.Prefs(
            speed = when (sp.getString(KEY_ASK_SPEED_UNIT, null)) {
                "ms" -> AskUnits.Speed.METRES_PER_SECOND
                "kmh" -> AskUnits.Speed.KM_PER_HOUR
                else -> AskUnits.Speed.KNOTS
            },
            depth = if (sp.getString(KEY_ASK_DEPTH_UNIT, null) == "feet") AskUnits.Depth.FEET else AskUnits.Depth.METRES,
        )

    /**
     * Which instance answers for a branch that has several, as `electrical.batteries=house`
     * lines. A boat with one of everything never needs this: the tree resolves `*` by itself.
     */
    val askInstances: Map<String, String>
        get() = (sp.getString(KEY_ASK_INSTANCES, null) ?: "")
            .split('\n', ';')
            .mapNotNull { line ->
                val at = line.indexOf('=')
                if (at <= 0) null else line.substring(0, at).trim() to line.substring(at + 1).trim()
            }
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
            .toMap()

    /** Who is asking, for an answer the whole crew hears. */
    val speakerName: String get() = name ?: android.os.Build.MODEL

    /**
     * This install's id in the server's access-request list. Stable, so pairing again replaces
     * the phone's own entry instead of leaving a row behind every time, and random, so it says
     * nothing about the phone.
     */
    val pairingClientId: String
        get() = sp.getString(KEY_ASK_CLIENT_ID, null) ?: java.util.UUID.randomUUID().toString().also {
            sp.edit { putString(KEY_ASK_CLIENT_ID, it) }
        }

    fun bool(key: String, default: Boolean): Boolean = sp.getBoolean(key, default)
    fun string(key: String): String? = sp.getString(key, null)
    fun put(key: String, value: Boolean) = sp.edit { putBoolean(key, value) }
    fun put(key: String, value: String?) = sp.edit { putString(key, value) }

    companion object {
        // Settings screen (see res/xml/preferences.xml and res/xml/app_restrictions.xml — the keys must match).
        const val KEY_CREW_NAME = "crew_name"
        const val KEY_NAME = "display_name"
        const val KEY_GROUP = "multicast_group"
        const val KEY_PORT = "lan_port"
        const val KEY_CHANNEL_KEY = "channel_key"
        const val KEY_CHANNEL_KEY_SHOW = "channel_key_show"
        const val KEY_CHANNEL_KEY_SHARE = "channel_key_share"
        const val KEY_CHANNEL_KEY_NEW = "channel_key_new"
        const val KEY_LEGACY_PASSPHRASE = "aware_passphrase"
        const val KEY_HOPS = "max_hops"

        const val KEY_HW_BUTTON = "hw_button"
        const val HW_OFF = "off"
        const val HW_HEADSET = "headset"
        const val HW_VOLUME = "volume"
        const val HW_BOTH = "both"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_AUDIO_ROUTE = "audio_route"
        const val KEY_HEADSET_CALL = "headset_call"
        const val KEY_HEADSET_VOX = "headset_vox"
        const val KEY_CUE_TONES = "cue_tones"
        const val KEY_PROXIMITY = "proximity_sensor"
        const val ROUTE_AUTO = "auto"
        const val ROUTE_SPEAKER = "speaker"
        const val ROUTE_EARPIECE = "earpiece"
        const val KEY_FULL_DUPLEX = "full_duplex"
        const val KEY_RELAY = "relay"
        const val KEY_OPUS = "opus"

        // Ask the boat (see res/xml/preferences.xml and res/xml/app_restrictions.xml).
        const val KEY_ASK_ENABLED = "ask_enabled"
        const val KEY_ASK_SERVER = "ask_server"
        const val KEY_ASK_TOKEN = "ask_token"
        const val KEY_ASK_SCOPE = "ask_scope"
        const val KEY_ASK_PAIR = "ask_pair"
        const val KEY_ASK_MODE = "ask_mode"
        const val KEY_ASK_SPEED_UNIT = "ask_speed_unit"
        const val KEY_ASK_DEPTH_UNIT = "ask_depth_unit"
        const val KEY_ASK_INSTANCES = "ask_instances"
        const val KEY_ASK_CLIENT_ID = "ask_client_id"
        const val ASK_MODE_JUST_ME = "just_me"
        const val ASK_MODE_CREW = "crew"
        const val ASK_SCOPE_NONE = "none"
        const val ASK_SCOPE_READ = "read"
        const val ASK_SCOPE_WRITE = "write"

        // Main screen state.
        const val KEY_USE_LAN = "use_lan"
        const val KEY_USE_BT = "use_bt"
        const val KEY_USE_AWARE = "use_aware"
        const val KEY_BT_PEER = "bt_peer"          // MAC address, or empty for "listen only"
    }
}
