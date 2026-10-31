package com.portal.wake.hybrid

import com.portal.commons.audio.HybridEvidence
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EvidenceRecorderTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun candidate(atMs: Long, woke: Boolean, audioBytes: Int = 1_000) = HybridEvidence.Candidate(
        atMs = atMs,
        gatePeak = 0.5f,
        spotted = listOf("HEY_JARVIS"),
        rival = null,
        vosk = "none",
        voskTranscript = "",
        wokeBy = if (woke) "sherpa" else null,
        spotterMs = 200,
        voskMs = 1_400,
        audio = ByteArray(audioBytes),
    )

    private fun lines(dir: File) = File(dir, EvidenceRecorder.LOG_FILE).readLines().map(::JSONObject)

    @Test
    fun `a candidate becomes one JSON line naming its WAV clip`() {
        val dir = tmp.newFolder()
        val r = EvidenceRecorder(dir, keepAudio = true, log = {})
        r.record(candidate(1_793_430_000_000, woke = true))
        r.awaitIdle()
        val line = lines(dir).single()
        assertEquals("candidate", line.getString("type"))
        assertEquals("2026-10-31T07:00:00Z", line.getString("at"))
        assertEquals("sherpa", line.getString("wokeBy"))
        assertTrue(line.getBoolean("woke"))
        val clip = File(dir, "clips/" + line.getString("clip"))
        assertEquals("cand-woke_1793430000000.wav", clip.name)
        assertEquals(44 + 1_000, clip.length().toInt())
        r.close()
    }

    @Test
    fun `without audio no clip is written`() {
        val dir = tmp.newFolder()
        val r = EvidenceRecorder(dir, keepAudio = false, log = {})
        r.record(candidate(1, woke = false))
        r.awaitIdle()
        assertTrue(lines(dir).single().isNull("clip"))
        assertTrue(!File(dir, "clips").exists() || File(dir, "clips").list().orEmpty().isEmpty())
        r.close()
    }

    @Test
    fun `clips past the cap delete the oldest first`() {
        val dir = tmp.newFolder()
        val r = EvidenceRecorder(dir, keepAudio = true, maxClipBytes = 2_500, log = {})
        (1L..4L).forEach { r.record(candidate(it, woke = false)) }
        r.awaitIdle()
        val left = File(dir, "clips").list().orEmpty().sorted()
        assertEquals(listOf("cand_3.wav", "cand_4.wav"), left)
        r.close()
    }

    @Test
    fun `the log rotates once past its size`() {
        val dir = tmp.newFolder()
        val r = EvidenceRecorder(dir, keepAudio = false, maxLogBytes = 100, log = {})
        repeat(3) { r.note("session", it.toLong(), mapOf("detector" to "hybrid")) }
        r.awaitIdle()
        assertTrue(File(dir, "${EvidenceRecorder.LOG_FILE}.1").isFile)
        r.close()
    }
}
