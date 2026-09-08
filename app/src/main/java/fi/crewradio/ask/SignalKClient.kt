package fi.crewradio.ask

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The boat's Signal K server, over the LAN.
 *
 * Everything here blocks, and is called from the `ptt-ask` thread — never the main one. There is
 * no client library and no dependency: one `HttpURLConnection`, a short timeout, and the platform
 * JSON parser. A question is one GET per top-level branch ("navigation" answers heading, speed and
 * position together), so the usual two-part question costs one request, not one per value.
 *
 * The server is on the boat's own network, which is also why the timeouts are short: if it does
 * not answer in a couple of seconds it is not there, and the crew would rather hear that than wait.
 */
class SignalKClient(
    private val base: String,
    private val token: String?,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) {

    /** Why a request did not produce an answer, in the terms the sheet explains it in. */
    enum class Failure {
        /** Nothing answered: wrong address, server down, or this phone is not on the boat's network. */
        UNREACHABLE,

        /** The server wants a token, or the one we have has been revoked. */
        UNAUTHORIZED,

        /** It answered, but not with Signal K. */
        BAD_RESPONSE,
    }

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Failed(val failure: Failure, val detail: String?) : Result<Nothing>
    }

    /**
     * Reads the given top-level branches of `vessels/self` into one tree.
     *
     * A branch the boat does not have is not a failure — a boat with no tanks simply has no
     * `tanks` — so the read succeeds as long as *something* answered. Only when every branch
     * failed is the whole read reported failed, with the first reason seen.
     */
    fun read(branches: List<String>): Result<SignalKTree> {
        if (branches.isEmpty()) return Result.Ok(SignalKTree.EMPTY)
        val tree = HashMap<String, Any?>()
        var firstFailure: Result.Failed? = null
        for (branch in branches) {
            when (val response = getJson(SignalKUrl.selfBranch(base, branch))) {
                is Result.Ok -> tree[branch] = toMap(response.value)
                is Result.Failed -> {
                    // A branch this boat does not publish comes back 404: not an error, just absent.
                    if (response.failure == Failure.BAD_RESPONSE && response.detail == NOT_FOUND) continue
                    if (firstFailure == null) firstFailure = response
                }
            }
        }
        if (tree.isEmpty()) return firstFailure ?: Result.Failed(Failure.BAD_RESPONSE, NOT_FOUND)
        return Result.Ok(SignalKTree(tree))
    }

    /**
     * Hands a finished sentence to the Crew Radio plugin, which speaks it on the channel. This is
     * the whole of "Whole crew" mode: the phone has already recognised, matched, converted and
     * worded the answer, and the plugin's announcement queue does the rest.
     */
    fun say(text: String): Result<Unit> {
        val body = JSONObject().put("text", text).put("priority", "normal").toString()
        return when (val response = post(SignalKUrl.pluginSay(base), body)) {
            is Result.Ok -> Result.Ok(Unit)
            is Result.Failed -> response
        }
    }

    /** Where an access request got to. */
    data class Access(val href: String?, val state: String?, val permission: String?, val token: String?)

    /**
     * Asks the server for a token, which someone with the admin UI open then approves. This is
     * Signal K's own device flow, and it is much better than asking the crew to paste a JWT:
     * nothing secret is ever shown, typed or read out.
     */
    fun requestAccess(clientId: String, description: String): Result<Access> {
        val body = JSONObject().put("clientId", clientId).put("description", description).toString()
        return when (val response = post(SignalKUrl.accessRequests(base), body)) {
            is Result.Ok -> Result.Ok(
                Access(
                    href = response.value.optString("href").takeIf { it.isNotEmpty() },
                    state = response.value.optString("state").takeIf { it.isNotEmpty() },
                    permission = null,
                    token = null,
                )
            )
            is Result.Failed -> response
        }
    }

    /** Polls the href a request came back with, until it is approved or denied. */
    fun pollAccess(href: String): Result<Access> =
        when (val response = getJson(base + href)) {
            is Result.Ok -> {
                val data = response.value.optJSONObject("accessRequest")
                Result.Ok(
                    Access(
                        href = href,
                        state = response.value.optString("state").takeIf { it.isNotEmpty() },
                        permission = data?.optString("permission")?.takeIf { it.isNotEmpty() },
                        token = data?.optString("token")?.takeIf { it.isNotEmpty() },
                    )
                )
            }
            is Result.Failed -> response
        }

    private fun getJson(url: String): Result<JSONObject> = request(url, "GET", null)

    private fun post(url: String, body: String): Result<JSONObject> = request(url, "POST", body)

    private fun request(url: String, method: String, body: String?): Result<JSONObject> {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                if (!token.isNullOrEmpty()) setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
                return Result.Failed(Failure.UNAUTHORIZED, "HTTP $code")
            }
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return Result.Failed(Failure.BAD_RESPONSE, NOT_FOUND)
            if (code !in 200..299) return Result.Failed(Failure.BAD_RESPONSE, "HTTP $code")
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (text.length > MAX_BODY) return Result.Failed(Failure.BAD_RESPONSE, "response too large")
            return Result.Ok(if (text.isBlank()) JSONObject() else JSONObject(text))
        } catch (e: IOException) {
            // Wrong address, nothing listening, or this phone is not on the boat's network.
            return Result.Failed(Failure.UNREACHABLE, e.message)
        } catch (e: JSONException) {
            return Result.Failed(Failure.BAD_RESPONSE, e.message)
        } catch (e: SecurityException) {
            // Cleartext refused by the network security policy, among other things.
            return Result.Failed(Failure.UNREACHABLE, e.message)
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 2_500
        const val READ_TIMEOUT_MS = 3_500

        /** A boat's whole `navigation` branch is a few kilobytes; a megabyte is something else. */
        const val MAX_BODY = 1 shl 20

        private const val NOT_FOUND = "HTTP 404"

        /**
         * The platform parser's objects as the plain maps [SignalKTree] walks. `JSONObject.NULL`
         * becomes a real null, and arrays are kept as lists so a value that happens to be one does
         * not read as a branch.
         */
        fun toMap(json: JSONObject): Map<String, Any?> = buildMap {
            for (key in json.keys()) put(key, unwrap(json.get(key)))
        }

        private fun unwrap(value: Any?): Any? = when (value) {
            JSONObject.NULL, null -> null
            is JSONObject -> toMap(value)
            is JSONArray -> (0 until value.length()).map { unwrap(value.get(it)) }
            else -> value
        }
    }
}
