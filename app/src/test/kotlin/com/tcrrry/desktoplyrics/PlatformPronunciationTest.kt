package com.tcrrry.desktoplyrics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlatformPronunciationTest {
    private val repository = DirectLyricsRepository()

    @Test fun netEaseReadsRomanizationWithoutMistakingTranslationForPronunciation() {
        val response = JSONObject().put("romalrc", JSONObject().put("lyric", "[00:01]kimi"))
            .put("tlyric", JSONObject().put("lyric", "[00:01]你"))
        assertEquals("[00:01]kimi", repository.netEaseRomanizedLyrics(response))
        assertEquals("", repository.netEaseRomanizedLyrics(JSONObject().put("tlyric", response.get("tlyric"))))
    }

    @Test fun netEaseSupportsEmptyPrimaryTrackAndWordFormatFallback() {
        val response = JSONObject().put("romalrc", JSONObject().put("lyric", ""))
            .put("yromalrc", JSONObject().put("lyric", "[1000,1000](1000,500,0)ki(1500,500,0)mi"))
        assertEquals("[00:01.000]kimi", repository.netEaseRomanizedLyrics(response))
    }

    @Test fun qqReadsItsSeparateRomanizationTrackAndRemovesQRCTimestamps() {
        val response = "<contentroma><![CDATA[[1000,1000]ki(1000,500)mi(1500,500)]]></contentroma>"
        assertEquals("[00:01.000]kimi", repository.qqRomanizedLyrics(response))
    }

    @Test fun absentOrCorruptOptionalQQTrackDoesNotInventPronunciation() {
        assertEquals("", repository.qqRomanizedLyrics("<contentts><![CDATA[[00:01]你]]></contentts>"))
        assertEquals("", repository.qqRomanizedLyrics("<contentroma><![CDATA[FFFFFFFFFFFFFFFF]]></contentroma>"))
    }

    @Test fun modernQQPlainPayloadUsesTheSameCanonicalParser() {
        assertEquals("[00:01]kimi", repository.decodeQqRomanizedTrack("[00:01]kimi"))
    }

    @Test fun savedProviderPayloadRetainsRomanization() {
        val result = DirectLyricsRepository.Result(lyrics = "[00:01]君", romanizedLyrics = "[00:01]kimi")
        assertEquals(result.romanizedLyrics, result.toJson().getString("romanizedLyrics"))
    }

    @Test fun lyricsAreDeliveredBeforeAnOptionalRequestAndSurviveItsFailure() {
        val original = DirectLyricsRepository.Result(lyrics = "[00:01]君だ", source = "QQ音乐")
        val delivered = mutableListOf<DirectLyricsRepository.Result>()
        val result = OptionalQqPronunciation.enrich(original, {
            assertEquals(listOf(original), delivered)
            throw java.io.IOException("optional endpoint unavailable")
        }, delivered::add)
        assertSame(original, result)
        assertEquals(listOf(original), delivered)
    }

    @Test fun aLatePronunciationTrackEnrichesTheSameRecording() {
        val original = DirectLyricsRepository.Result(lyrics = "[00:01]君だ", source = "QQ音乐", recordId = "id")
        val delivered = mutableListOf<DirectLyricsRepository.Result>()
        val result = OptionalQqPronunciation.enrich(original, { "[00:01]kimi da" }, delivered::add)
        assertEquals(original, delivered.first())
        assertEquals(result, delivered.last())
        assertEquals(original.recordId, result.recordId)
        assertEquals("[00:01]kimi da", result.romanizedLyrics)
    }

    @Test fun existingPronunciationAndChineseLyricsDoNotRequestAnotherTrack() {
        for (original in listOf(DirectLyricsRepository.Result(lyrics = "[00:01]中文歌曲"),
            DirectLyricsRepository.Result(lyrics = "[00:01]君だ", romanizedLyrics = "[00:01]kimi da"))) {
            val result = OptionalQqPronunciation.enrich(original, { fail("unnecessary request"); "" }, {})
            assertSame(original, result)
        }
    }
}
