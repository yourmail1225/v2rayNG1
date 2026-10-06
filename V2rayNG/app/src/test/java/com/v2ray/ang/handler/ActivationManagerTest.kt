package com.v2ray.ang.handler

import com.v2ray.ang.util.ActivationCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.reset
import org.mockito.kotlin.whenever

class ActivationManagerTest {

    private val settingsValues = mutableMapOf<String, String>()

    @Before
    fun prepareStorage() {
        reset(settings)
        whenever(settings.decodeString(any())).thenAnswer { settingsValues[it.getArgument<String>(0)] }
        whenever(settings.decodeString(any(), any<String>())).thenAnswer {
            settingsValues[it.getArgument<String>(0)] ?: it.getArgument<String>(1)
        }
        whenever(settings.encode(any<String>(), any<String>())).thenAnswer {
            settingsValues[it.getArgument<String>(0)] = it.getArgument(1)
            true
        }
        whenever(settings.encode(any<String>(), any<Boolean>())).thenAnswer {
            settingsValues[it.getArgument<String>(0)] = it.getArgument<Boolean>(1).toString()
            true
        }
        whenever(settings.decodeBool(any<String>(), any<Boolean>())).thenAnswer {
            settingsValues[it.getArgument<String>(0)]?.toBoolean() ?: it.getArgument<Boolean>(1)
        }
    }

    private fun setupCode(json: String): String =
        ActivationCodec.encode(json.toByteArray(Charsets.UTF_8))

    private fun row(
        username: String = "sa1234",
        max: Long = 2L,
        count: Long = 0L,
        used: Long = 0L,
        entries: Int = 1,
        lastSeen: String? = null,
    ): String {
        val list = (1..entries).joinToString(",") { "{\"content\":\"vless://node$it@1.1.1.1:443#n$it\"}" }
        val seen = if (lastSeen == null) "" else ",\"lastSeen\":\"$lastSeen\""
        val payload = "{" +
            "\"password\":\"1234\"," +
            "\"expiryEpochMinute\":29849549," +
            "\"dataLimitBytes\":10737418240," +
            "\"activation\":{" +
            "\"username\":\"$username\"," +
            "\"maxActivations\":$max," +
            "\"fetchCount\":$count," +
            "\"usedBytes\":$used," +
            "\"updatedAt\":\"2026-01-01T00:00:00\"" +
            seen +
            "}," +
            "\"entries\":[$list]" +
            "}"
        return "#V2RAYNG-LOCK-PACKAGE-BEGIN#\n$payload\n#V2RAYNG-LOCK-PACKAGE-END#\n"
    }

    @Test
    fun parseSetupReturnsNullForBlankAndNullInput() {
        assertNull(ActivationManager.parseSetup(null))
        assertNull(ActivationManager.parseSetup(""))
        assertNull(ActivationManager.parseSetup("   "))
    }

    @Test
    fun parseSetupReturnsNullForMalformedCode() {
        assertNull(ActivationManager.parseSetup("!!!not-base64!!!"))
    }

    @Test
    fun parseSetupReadsGithubTargetFromValidCode() {
        val setup = ActivationManager.parseSetup(
            setupCode("{\"v\":2,\"g\":\"ghp_x\",\"r\":\"acct/txt\",\"b\":\"dev\",\"d\":\"subs\"}"),
        )
        assertEquals("ghp_x", setup?.token)
        assertEquals("acct/txt", setup?.repo)
        assertEquals("dev", setup?.branch)
        assertEquals("subs", setup?.rowDir)
    }

    @Test
    fun parseSetupRejectsLegacyV1PanelCode() {
        // A v1 blob pointed at an HTTP panel server; it must not be honoured any more.
        assertNull(ActivationManager.parseSetup(setupCode("{\"v\":1,\"s\":\"http://127.0.0.1:5050\"}")))
    }

    @Test
    fun parseSetupRejectsVersion2WithoutRepository() {
        assertNull(ActivationManager.parseSetup(setupCode("{\"v\":2,\"g\":\"ghp_x\"}")))
    }

    @Test
    fun parseSetupIgnoresBlankFields() {
        val setup = ActivationManager.parseSetup(setupCode("{\"v\":2,\"r\":\"acct/txt\",\"g\":\"\",\"b\":\"\",\"d\":\"\"}"))
        assertEquals("acct/txt", setup?.repo)
        assertNull(setup?.token)
        assertNull(setup?.branch)
    }

    @Test
    fun resolveRepositoryPersistsTokenAndPath() {
        val config = setupCode("{\"v\":2,\"g\":\"ghp_x\",\"r\":\"acct/txt\",\"b\":\"dev\",\"d\":\"subs\"}")
        assertEquals("acct/txt", ActivationManager.resolveRepository(config))
        assertEquals("ghp_x", settingsValues[ActivationManagerTest.KEY_TOKEN])
        assertEquals("acct/txt", settingsValues[ActivationManagerTest.KEY_REPO])
        assertEquals("dev", settingsValues[ActivationManagerTest.KEY_BRANCH])
        assertEquals("subs", settingsValues[ActivationManagerTest.KEY_ROW_DIR])
    }

    @Test
    fun resolveRepositoryTrimsRowDirectorySlashes() {
        ActivationManager.resolveRepository(setupCode("{\"v\":2,\"r\":\"a/b\",\"d\":\"/subs/\"}"))
        assertEquals("subs", settingsValues[ActivationManagerTest.KEY_ROW_DIR])
    }

    @Test
    fun resolveRepositoryKeepsBuiltInValuesWhenConfigBlank() {
        assertEquals(ActivationManager.DEFAULT_REPO, ActivationManager.resolveRepository(null))
        assertEquals(ActivationManager.DEFAULT_REPO, ActivationManager.resolveRepository(""))
        assertNull(settingsValues[ActivationManagerTest.KEY_REPO])
        assertNull(settingsValues[ActivationManagerTest.KEY_TOKEN])
    }

    @Test
    fun masterCodeActivatesWithoutReadingGitHub() = runBlocking(Dispatchers.IO) {
        val outcome = ActivationManager.activate("  ${ActivationManager.MASTER_CODE}  ", null)

        assertTrue(outcome is ActivationOutcome.Master)
        assertNull(
            "a master unlock must not need any repository setting",
            settingsValues[ActivationManagerTest.KEY_REPO],
        )
    }

    @Test
    fun blankCodeIsDeniedBeforeAnyRequest() = runBlocking(Dispatchers.IO) {
        val outcome = ActivationManager.activate("   ", null)

        assertEquals(ActivationOutcome.Error(ActivationErrorKind.DENIED), outcome)
        assertNull(settingsValues[ActivationManagerTest.KEY_REPO])
    }

    @Test
    fun codeWithPathTraversalIsDeniedBeforeAnyRequest() = runBlocking(Dispatchers.IO) {
        listOf("../secrets", "a/b", "a\\b", "with space", ".hidden", "x".repeat(65)).forEach { code ->
            assertEquals(
                "code must be rejected before a request: $code",
                ActivationOutcome.Error(ActivationErrorKind.DENIED),
                ActivationManager.activate(code, null),
            )
        }
        assertNull(settingsValues[ActivationManagerTest.KEY_REPO])
    }

    @Test
    fun markActivatedStoresCodeAndSubscription() {
        ActivationManager.markActivated(ActivationManager.MODE_CODE, "sa1234", "sa1234")

        assertEquals("true", settingsValues[ActivationManagerTest.KEY_DONE])
        assertEquals(ActivationManager.MODE_CODE, settingsValues[ActivationManagerTest.KEY_MODE])
        assertEquals("sa1234", settingsValues[ActivationManagerTest.KEY_CODE])
        assertEquals("sa1234", settingsValues[ActivationManagerTest.KEY_SUB_ID])
        assertTrue(ActivationManager.isActivated())
    }

    @Test
    fun isActivatedIsFalseBeforeActivation() {
        assertFalse(ActivationManager.isActivated())
    }

    @Test
    fun rowActivationReadsCounters() {
        val status = RowActivation.read(row(max = 3, count = 2, used = 4096))

        assertEquals("sa1234", status.username)
        assertEquals(3L, status.maxActivations)
        assertEquals(2L, status.fetchCount)
        assertEquals(4096L, status.usedBytes)
        assertFalse(status.exhausted)
    }

    @Test
    fun rowActivationReadsTheLastConnection() {
        val status = RowActivation.read(row(lastSeen = "2026-03-03T08:30:00Z"))

        assertEquals("2026-03-03T08:30:00Z", status.lastSeen)
    }

    @Test
    fun aRowWithoutAReportedConnectionHasNoLastConnection() {
        assertEquals("", RowActivation.read(row()).lastSeen)
        assertEquals("", RowActivation.read(LEGACY_ROW).lastSeen)
    }

    @Test
    fun rowActivationOfLegacyRowWithoutBlockIsUnlimitedAndZero() {
        val status = RowActivation.read(LEGACY_ROW)

        assertEquals(0L, status.fetchCount)
        assertEquals(0L, status.maxActivations)
        assertFalse("a row without a cap must not block activation", status.exhausted)
    }

    @Test
    fun exhaustedOnlyWhenCapReached() {
        assertFalse(RowActivation(maxActivations = 2, fetchCount = 1).exhausted)
        assertTrue(RowActivation(maxActivations = 2, fetchCount = 2).exhausted)
        assertTrue(RowActivation(maxActivations = 2, fetchCount = 5).exhausted)
        assertFalse("0 means unlimited", RowActivation(maxActivations = 0, fetchCount = 99).exhausted)
    }

    @Test
    fun aRowBelongingToAnotherCodeIsRejected() {
        assertTrue(RowActivation.read(row(username = "other")).belongsToOtherCode("sa1234"))
        assertFalse(RowActivation.read(row(username = "sa1234")).belongsToOtherCode("sa1234"))
        assertFalse("a legacy row without a name belongs to whoever fetched it", RowActivation().belongsToOtherCode("sa1234"))
    }

    @Test
    fun onlyARowWithAFiniteCapNeedsAWriteToken() {
        assertTrue(RowActivation.read(row(max = 2)).counted)
        assertFalse(RowActivation.read(row(max = 0)).counted)
        assertFalse("a legacy row has no block to write back to", RowActivation.read(LEGACY_ROW).counted)
    }

    @Test
    fun mergeIntoAdvancesCountersAndKeepsConfigs() {
        val original = row(entries = 2)
        val current = RowActivation.read(original)
        val merged = RowActivation.mergeInto(
            original,
            current.copy(fetchCount = current.fetchCount + 1, usedBytes = 8192),
        )

        val payload = payloadOf(merged)
        assertTrue("configs must survive", payload.contains("vless://node1@1.1.1.1:443#n1"))
        assertTrue("configs must survive", payload.contains("vless://node2@1.1.1.1:443#n2"))
        assertTrue("shared quota must survive", payload.contains("\"dataLimitBytes\":10737418240"))
        assertTrue("password must survive", payload.contains("\"password\":\"1234\""))
        assertTrue("expiry must survive", payload.contains("\"expiryEpochMinute\":29849549"))
        assertTrue(merged.startsWith("#V2RAYNG-LOCK-PACKAGE-BEGIN#"))
        assertTrue(merged.trimEnd().endsWith("#V2RAYNG-LOCK-PACKAGE-END#"))

        val after = RowActivation.read(merged)
        assertEquals(1L, after.fetchCount)
        assertEquals(8192L, after.usedBytes)
        assertEquals(2L, after.maxActivations)
        assertTrue("the panel needs a last-update stamp", payloadOf(merged).contains("\"updatedAt\""))
    }

    @Test
    fun mergeIntoWritesTheLastConnectionOnlyWhenReported() {
        val original = row()
        val merged = RowActivation.mergeInto(
            original,
            RowActivation.read(original).copy(lastSeen = "2026-03-03T08:30:00Z"),
        )

        assertEquals("2026-03-03T08:30:00Z", RowActivation.read(merged).lastSeen)

        val unreported = RowActivation.mergeInto(original, RowActivation.read(original))
        assertTrue(
            "an unreported connection must not add the key the panel reads",
            !payloadOf(unreported).contains("lastSeen"),
        )
    }

    @Test
    fun mergeIntoLeavesRowWithoutActivationBlockUntouched() {
        assertEquals(LEGACY_ROW, RowActivation.mergeInto(LEGACY_ROW, RowActivation(fetchCount = 1)))
    }

    @Test
    fun mergeIntoToleratesGarbageCounters() {
        val bad = "#V2RAYNG-LOCK-PACKAGE-BEGIN#\n" +
            "{\"activation\":{\"maxActivations\":\"many\",\"fetchCount\":null,\"usedBytes\":-5}}\n" +
            "#V2RAYNG-LOCK-PACKAGE-END#\n"
        val status = RowActivation.read(bad)

        assertEquals(0L, status.maxActivations)
        assertEquals(0L, status.fetchCount)
        assertEquals("a negative counter is meaningless, not a credit", 0L, status.usedBytes)
    }

    @Test
    fun reportUsageIsSkippedWithoutToken() {
        ActivationManager.markActivated(ActivationManager.MODE_CODE, "sa1234", "sa1234")
        // No token stored: nothing is written, and no exception escapes.
        ActivationManager.reportUsage()
    }

    @Test
    fun rowUrlIsTheRawRowOfTheCode() {
        // The activated subscription stores this URL, so it must address the row file and
        // not the API or the repository root, and it must be usable as a subscription URL.
        val url = ActivationManager.rowUrl("sa1234")

        assertTrue(url, url.startsWith("https://raw.githubusercontent.com/"))
        assertTrue(url, url.endsWith("/sa1234.row"))
    }

    @Test
    fun reportActivationIsSkippedWithoutToken() {
        ActivationManager.reportActivation("sa1234", "sa1234")
    }

    private fun payloadOf(rowText: String): String = rowText
        .lineSequence()
        .dropWhile { it.trim() != "#V2RAYNG-LOCK-PACKAGE-BEGIN#" }
        .drop(1)
        .takeWhile { it.trim() != "#V2RAYNG-LOCK-PACKAGE-END#" }
        .joinToString("\n")

    companion object {
        private val LEGACY_ROW = "#V2RAYNG-LOCK-PACKAGE-BEGIN#\n" +
            "{\"password\":\"\",\"entries\":[{\"content\":\"vless://a\"}]}\n" +
            "#V2RAYNG-LOCK-PACKAGE-END#\n"
        private const val KEY_DONE = "pref_activation_done"
        private const val KEY_MODE = "pref_activation_mode"
        private const val KEY_CODE = "pref_activation_code"
        private const val KEY_SUB_ID = "pref_activation_subscription_id"
        private const val KEY_TOKEN = "pref_github_token"
        private const val KEY_REPO = "pref_github_repo"
        private const val KEY_BRANCH = "pref_github_branch"
        private const val KEY_ROW_DIR = "pref_github_row_dir"
        private val settings get() = MmkvTestHandles.settings

        @BeforeClass
        @JvmStatic
        fun initializeHandles() {
            MmkvTestHandles.bind()
        }
    }
}