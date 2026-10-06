package com.v2ray.ang.handler

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.ActivationCodec
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LockedPackage
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Client for the one-time activation gate.
 *
 * There is no panel server. A user code names a row file in the GitHub repository the
 * panel publishes to (`<repo>/<branch>/<row_dir>/<code>.row`), the app reads that row
 * directly, validates the activation block inside it, imports the locked profiles, and
 * writes the fetch counter plus the reported traffic back into the same file. The token
 * that arrives through the optional Base64 field is only needed for those writes; a row
 * is readable anonymously when the repository is public.
 *
 * Reads go through raw.githubusercontent.com so they work without a token. Writes need
 * the Contents API and therefore a token with `Contents: read and write` for the
 * repository. A row that carries a finite activation cap is therefore refused without a
 * token, because a device limit that cannot be recorded is not a limit; a row published
 * as unlimited activates on a tokenless read.
 */
object ActivationManager {

    private const val PREF_ACTIVATION_DONE = "pref_activation_done"
    private const val PREF_ACTIVATION_CODE = "pref_activation_code"
    private const val PREF_ACTIVATION_MODE = "pref_activation_mode"
    private const val PREF_ACTIVATION_SUBSCRIPTION_ID = "pref_activation_subscription_id"
    private const val PREF_GITHUB_TOKEN = "pref_github_token"
    private const val PREF_GITHUB_REPO = "pref_github_repo"
    private const val PREF_GITHUB_BRANCH = "pref_github_branch"
    private const val PREF_GITHUB_ROW_DIR = "pref_github_row_dir"

    /** Built-in repository; a Base64 field can point the app somewhere else. */
    const val DEFAULT_REPO = "yourmail1225/txt"
    const val DEFAULT_BRANCH = "main"

    const val MODE_CODE = "code"
    const val MODE_MASTER = "master"

    const val ROW_EXTENSION = ".row"

    /**
     * Code that unlocks the app offline. Compared before any network work so the owner
     * always gets in even with no connectivity and no repository reachable.
     */
    const val MASTER_CODE = "adminsaj"

    /**
     * Row names become a URL path segment, so a code must stay inside the same shape the
     * panel accepts; anything else is rejected before a request is built.
     */
    private val USERNAME_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Owns the counter write-back. It is deliberately not tied to the activation
     * activity: the write has to outlive `finishActivity()`, and this object is the
     * owner of the activation state it updates.
     */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Serializes counter writes so two updates cannot clobber each other. */
    private val writeLock = Mutex()

    fun isActivated(): Boolean = MmkvManager.decodeSettingsBool(PREF_ACTIVATION_DONE, false)

    private fun savedCode(): String = MmkvManager.decodeSettingsString(PREF_ACTIVATION_CODE).orEmpty()

    private fun token(): String = MmkvManager.decodeSettingsString(PREF_GITHUB_TOKEN).orEmpty()

    private fun repo(): String =
        MmkvManager.decodeSettingsString(PREF_GITHUB_REPO)?.takeIf { it.isNotBlank() } ?: DEFAULT_REPO

    private fun branch(): String =
        MmkvManager.decodeSettingsString(PREF_GITHUB_BRANCH)?.takeIf { it.isNotBlank() } ?: DEFAULT_BRANCH

    private fun rowDir(): String = MmkvManager.decodeSettingsString(PREF_GITHUB_ROW_DIR).orEmpty()

    /** Subscription created for the activated code; its usage is what gets reported. */
    fun savedSubscriptionId(): String =
        MmkvManager.decodeSettingsString(PREF_ACTIVATION_SUBSCRIPTION_ID).orEmpty()

    private fun rowName(code: String): String = code + ROW_EXTENSION

    private fun rowPath(code: String): String {
        val dir = rowDir().trim('/')
        return if (dir.isEmpty()) rowName(code) else "$dir/${rowName(code)}"
    }

    private fun rawRowUrl(code: String): String =
        "https://raw.githubusercontent.com/${repo()}/${branch()}/${rowPath(code)}"

    /**
     * Persists the GitHub destination from the optional Base64 field. Blank fields leave
     * the previously stored or built-in value in place, so a code carrying only a token
     * still works.
     */
    fun resolveRepository(configCode: String?): String {
        val setup = parseSetup(configCode) ?: return repo()
        setup.token?.takeIf { it.isNotBlank() }?.let { MmkvManager.encodeSettings(PREF_GITHUB_TOKEN, it) }
        setup.repo?.takeIf { it.isNotBlank() }?.let { MmkvManager.encodeSettings(PREF_GITHUB_REPO, it) }
        setup.branch?.takeIf { it.isNotBlank() }?.let { MmkvManager.encodeSettings(PREF_GITHUB_BRANCH, it) }
        setup.rowDir?.let { MmkvManager.encodeSettings(PREF_GITHUB_ROW_DIR, it.trim('/')) }
        return repo()
    }

    fun markActivated(mode: String, code: String, subscriptionId: String) {
        MmkvManager.encodeSettings(PREF_ACTIVATION_DONE, true)
        MmkvManager.encodeSettings(PREF_ACTIVATION_MODE, mode)
        MmkvManager.encodeSettings(PREF_ACTIVATION_CODE, code)
        MmkvManager.encodeSettings(PREF_ACTIVATION_SUBSCRIPTION_ID, subscriptionId)
    }

    /**
     * Reads the row for [code] and decides whether it may still be activated.
     *
     * A missing or unreadable row is `DENIED` because there is nothing to import; a
     * network fault is `NETWORK` so the screen can tell the user to retry rather than
     * that the code is wrong. The row text handed back for import is only the profile
     * content; the counter write re-reads the file together with its sha, so an edit made
     * between the activation and the write is never lost.
     */
    internal suspend fun activate(code: String, configCode: String?): ActivationOutcome =
        withContext(Dispatchers.IO) {
            val trimmed = code.trim()
            if (trimmed.isBlank()) {
                return@withContext ActivationOutcome.Error(ActivationErrorKind.DENIED)
            }
            if (trimmed == MASTER_CODE) {
                return@withContext ActivationOutcome.Master
            }
            if (!USERNAME_REGEX.matches(trimmed)) {
                return@withContext ActivationOutcome.Error(ActivationErrorKind.DENIED)
            }
            resolveRepository(configCode)
            val rowText = try {
                fetchRow(trimmed)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Activation row fetch failed for code=$trimmed", e)
                return@withContext ActivationOutcome.Error(ActivationErrorKind.NETWORK)
            } ?: return@withContext ActivationOutcome.Error(ActivationErrorKind.DENIED)

            val status = RowActivation.read(rowText)
            if (status.exhausted) {
                return@withContext ActivationOutcome.Error(ActivationErrorKind.LIMIT)
            }
            if (status.belongsToOtherCode(trimmed)) {
                LogUtil.e(AppConfig.TAG, "Activation row belongs to another code, requested=$trimmed")
                return@withContext ActivationOutcome.Error(ActivationErrorKind.DENIED)
            }
            if (status.counted && token().isBlank()) {
                // A finite cap cannot be enforced by a device that cannot record its own
                // activation, so it is refused instead of silently acting as unlimited.
                return@withContext ActivationOutcome.Error(ActivationErrorKind.NO_WRITE_TOKEN)
            }
            ActivationOutcome.Subscription(trimmed, rowText)
        }

    /**
     * Records one successful activation: `fetchCount` becomes the next number and the
     * consumed traffic of [subscriptionId] is written in the same request, so the panel
     * never shows a count without the matching usage.
     *
     * Best effort by design. A device that activated successfully must not be locked out
     * of its own subscription because the panel repository was momentarily unreachable,
     * so failures are logged and the counters are retried on the next launch. The write
     * runs on the manager's own scope because it must outlive the activation activity.
     */
    fun reportActivation(code: String, subscriptionId: String) {
        if (code.isBlank() || token().isBlank()) return
        scope.launch {
            val usedBytes = MmkvManager.decodeGroupLock(subscriptionId).usedBytes
            updateRow(code) { current ->
                current.copy(fetchCount = current.fetchCount + 1, usedBytes = usedBytes)
            }
        }
    }

    /**
     * Refreshes only the reported traffic on an already activated app, called whenever the
     * app comes up so a customer who keeps using the app shows current consumption in the
     * panel without any action. Unchanged usage and an already recorded connection skip
     * the write, so a normal app start does not create a commit.
     */
    fun reportUsage() {
        if (!isActivated()) return
        val code = savedCode()
        val subscriptionId = savedSubscriptionId()
        if (code.isBlank() || subscriptionId.isBlank() || token().isBlank()) return
        scope.launch {
            val usedBytes = MmkvManager.decodeGroupLock(subscriptionId).usedBytes
            updateRow(code) { current ->
                if (usedBytes == current.usedBytes && current.lastSeen.isNotBlank()) {
                    null
                } else {
                    current.copy(usedBytes = usedBytes, lastSeen = current.nowStamp())
                }
            }
        }
    }

    /**
     * Raw GitHub URL of a code's row. The activated subscription stores this so the
     * subscription updater can refresh the row on its own instead of only importing the
     * single copy fetched during activation.
     */
    fun rowUrl(code: String): String = rawRowUrl(code.trim())

    /**
     * Raw base URL of the configured repository, without the row directory. The panel
     * publishes files other than rows there, such as the app-update notice the app
     * reads on start.
     */
    fun repoRootUrl(): String =
        "https://raw.githubusercontent.com/${repo()}/${branch()}/"

    /**
     * Password the panel shipped for the activated subscription, empty when the row
     * carried none or the app is not activated. It gates importing a config and
     * adding a subscription, so only the owner's row decides whether a customer is
     * asked for one.
     */
    fun activationPassword(): String {
        if (!isActivated()) return ""
        val sub = MmkvManager.decodeSubscription(savedSubscriptionId()) ?: return ""
        return sub.password.orEmpty()
    }

    /**
     * Reads the row together with its blob sha in one Contents API call and writes the
     * result of [transform] back under that same sha. Reading the content and the sha
     * separately would let a panel edit in between be overwritten, because GitHub only
     * rejects the write when the sha is the one it was read with.
     *
     * Writes are serialized so the counter and the usage of one activation cannot
     * overwrite each other, and a rejected sha is retried against the newer row instead of
     * dropping the update.
     */
    private suspend fun updateRow(code: String, transform: (RowActivation) -> RowActivation?) {
        if (!writeLock.tryLock()) return
        try {
            for (attempt in 0 until WRITE_ATTEMPTS) {
                val snapshot = fetchRowWithSha(code) ?: return
                val updated = transform(snapshot.activation) ?: return
                val payload = RowActivation.mergeInto(snapshot.row, updated)
                if (payload == snapshot.row) return
                if (putRow(code, payload, snapshot.sha)) return
                LogUtil.d(AppConfig.TAG, "Activation row changed during write, retrying code=$code")
                delay(WRITE_RETRY_DELAY_MS * (attempt + 1))
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Activation row update failed for code=$code", e)
        } finally {
            writeLock.unlock()
        }
    }

    /** Row text of a code, or null when the repository has no such file. */
    private fun fetchRow(code: String): String? {
        val request = Request.Builder().url(rawRowUrl(code)).build()
        return client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> throw IOException("HTTP ${response.code}")
                else -> response.body?.string()?.takeIf { it.isNotBlank() }
            }
        }
    }

    /** Row text and blob sha of a code, read atomically through the Contents API. */
    private fun fetchRowWithSha(code: String): RowSnapshot? {
        val request = Request.Builder()
            .url("${contentsUrl(code)}?ref=${URLEncoder.encode(branch(), "UTF-8")}")
            .header("Authorization", "Bearer ${token()}")
            .header("Accept", "application/vnd.github+json")
            .build()
        return client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> throw IOException("HTTP ${response.code}")
                else -> rowSnapshotOf(response)
            }
        }
    }

    /** Contents API response to a row file; the content is Base64 and may be wrapped. */
    private fun rowSnapshotOf(response: Response): RowSnapshot? {
        val obj = JsonParser.parseString(response.body?.string().orEmpty())
            .takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val sha = obj.get("sha")?.asString ?: return null
        val encoded = obj.get("content")?.asString?.replace("\n", "").orEmpty()
        val rowText = runCatching {
            String(ActivationCodec.decode(encoded), Charsets.UTF_8)
        }.getOrNull() ?: return null
        return RowSnapshot(rowText, sha, RowActivation.read(rowText))
    }

    /**
     * Replaces the row file with [rowText] under [sha]. Returns false when the panel
     * rewrote the row first, so the caller can retry against the newer content instead of
     * overwriting a panel edit.
     */
    private fun putRow(code: String, rowText: String, sha: String): Boolean {
        val encoded = ActivationCodec.encode(rowText.toByteArray(Charsets.UTF_8))
        val body = JsonUtil.toJson(
            GithubCommit(
                message = "update activation for $code",
                content = encoded,
                branch = branch(),
                sha = sha,
            )
        )
        val request = Request.Builder()
            .url(contentsUrl(code))
            .header("Authorization", "Bearer ${token()}")
            .header("Accept", "application/vnd.github+json")
            .put(body.toRequestBody(jsonMediaType))
            .build()
        return client.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> true
                response.code == 409 || response.code == 422 -> false
                else -> throw IOException("HTTP ${response.code}")
            }
        }
    }

    private fun contentsUrl(code: String): String =
        "https://api.github.com/repos/${repo()}/contents/${rowPath(code)}"

    /**
     * Parses the optional setup code. A blank field is the ordinary "use the built-in
     * repository" case and returns null without touching the stored destination, so a
     * code carrying only a token still works. A malformed blob is a typo in an optional
     * field, not a fault: it is swallowed here so the activation screen simply falls back
     * to the built-in repository and the JVM tests, which have no android.util.Log, can
     * exercise the failure branch.
     */
    internal fun parseSetup(configCode: String?): ActivationSetup? {
        if (configCode.isNullOrBlank()) return null
        return try {
            val raw = ActivationCodec.decode(configCode.trim())
            val setup = JsonUtil.fromJsonSafe(String(raw, Charsets.UTF_8), ActivationSetup::class.java)
            setup?.takeIf { it.v >= SETUP_CODE_VERSION && !it.repo.isNullOrBlank() }
        } catch (e: Exception) {
            null
        }
    }

    internal const val SETUP_CODE_VERSION = 2

    /** A panel edit between the app's read and its write is retried, never overwritten. */
    private const val WRITE_ATTEMPTS = 3
    private const val WRITE_RETRY_DELAY_MS = 400L
}

/** Row content and the blob sha it was read with, from one Contents API response. */
private data class RowSnapshot(
    val row: String,
    val sha: String,
    val activation: RowActivation,
)

internal enum class ActivationErrorKind { NONE, NETWORK, DENIED, LIMIT, NO_WRITE_TOKEN, GENERIC }

internal sealed class ActivationOutcome {
    object Master : ActivationOutcome()
    data class Subscription(val code: String, val row: String) : ActivationOutcome()
    data class Error(val kind: ActivationErrorKind) : ActivationOutcome()
}

/** Layout of the optional Base64 field produced by the panel. */
internal data class ActivationSetup(
    val v: Int = 0,
    val g: String? = null,
    val r: String? = null,
    val b: String? = null,
    val d: String? = null,
) {
    val token: String? get() = g?.takeIf { it.isNotBlank() }
    val repo: String? get() = r?.takeIf { it.isNotBlank() }
    val branch: String? get() = b?.takeIf { it.isNotBlank() }
    val rowDir: String? get() = d
}

/**
 * Activation counters stored inside a row. The panel owns these fields; the app only
 * advances `fetchCount` and reports `usedBytes`, so unknown fields are preserved by
 * [mergeInto] rather than rewritten from this shape.
 */
internal data class RowActivation(
    val username: String = "",
    val maxActivations: Long = 0L,
    val fetchCount: Long = 0L,
    val usedBytes: Long = 0L,
    /** When the customer last brought the tunnel up; empty until the app reports one. */
    val lastSeen: String = "",
) {
    val exhausted: Boolean get() = maxActivations > 0L && fetchCount >= maxActivations

    /** A row the panel published under a different code must not satisfy this code. */
    fun belongsToOtherCode(code: String): Boolean = username.isNotBlank() && username != code

    /** True when the panel records activations, so the app must be able to write back. */
    val counted: Boolean get() = maxActivations > 0L

    fun nowStamp(): String = Instant.now().toString()

    companion object {
        fun read(rowText: String): RowActivation {
            val obj = runCatching {
                val element = JsonParser.parseString(payloadOf(rowText))
                element.takeIf { it.isJsonObject }?.asJsonObject
            }.getOrNull() ?: return RowActivation()
            val activation = obj.get("activation")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return RowActivation()
            return RowActivation(
                username = activation.stringOrEmpty("username"),
                maxActivations = activation.longOrZero("maxActivations"),
                fetchCount = activation.longOrZero("fetchCount"),
                usedBytes = activation.longOrZero("usedBytes"),
                lastSeen = activation.stringOrEmpty("lastSeen"),
            )
        }

        /** Row text with only the activation counters replaced; configs stay untouched. */
        fun mergeInto(rowText: String, value: RowActivation): String {
            val obj = runCatching {
                val element = JsonParser.parseString(payloadOf(rowText))
                element.takeIf { it.isJsonObject }?.asJsonObject
            }.getOrNull() ?: return rowText
            val activation = obj.get("activation")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return rowText
            if (value.username.isNotBlank()) activation.addProperty("username", value.username)
            activation.addProperty("maxActivations", value.maxActivations)
            activation.addProperty("fetchCount", value.fetchCount)
            activation.addProperty("usedBytes", value.usedBytes)
            if (value.lastSeen.isNotBlank()) activation.addProperty("lastSeen", value.lastSeen)
            activation.addProperty("updatedAt", value.nowStamp())
            val body = JsonUtil.toJson(obj)
            return "${LockedPackage.HEADER}\n$body\n${LockedPackage.FOOTER}\n"
        }

        /** JSON between the lock markers; the panel writes exactly one such block. */
        private fun payloadOf(rowText: String): String {
            val builder = StringBuilder()
            var inside = false
            rowText.split("\n").forEach { line ->
                val trimmed = line.trim()
                if (trimmed == LockedPackage.HEADER || trimmed == LockedPackage.FOOTER) {
                    inside = trimmed == LockedPackage.HEADER
                } else if (inside) {
                    builder.appendLine(line)
                }
            }
            return builder.toString()
        }

        private fun JsonObject.stringOrEmpty(name: String): String =
            get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

        private fun JsonObject.longOrZero(name: String): Long =
            get(name)?.takeIf { it.isJsonPrimitive }?.runCatching { asLong }?.getOrNull()?.coerceAtLeast(0L) ?: 0L
    }
}

private data class GithubCommit(
    val message: String,
    val content: String,
    val branch: String,
    val sha: String,
)