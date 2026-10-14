package cn.edu.cppu.libraryautobooker.ocr

import org.junit.Assert.*
import org.junit.Test

class CtcDecoderTest {
    private val charset = listOf("") + (0..9).map { it.toString() } + "X"

    private fun logits(vararg indices: Int): FloatArray =
        indices.flatMap { winner -> List(charset.size) { if (it == winner) 8f else -8f } }.toFloatArray()

    @Test fun duplicateDigitsAreSeparatedOnlyByBlank() {
        val result = CtcDecoder.decode(logits(2, 2, 0, 2, 2, 3, 0), 7, charset)
        assertEquals("112", result.text)
        assertTrue(result.confidence > 0.99)
    }

    @Test fun digitsRangeDoesNotSkipFirstZeroOrConfuseBlankIndex() {
        assertEquals("012", CtcDecoder.decode(logits(1, 1, 0, 2, 3), 5, charset, true).text)
    }

    @Test fun restrictionDoesNotArtificiallyRaiseConfidence() {
        val scores = FloatArray(charset.size) { -10f }
        scores[11] = 9f
        scores[3] = 7f
        val unrestricted = CtcDecoder.decode(scores, 1, charset)
        val restricted = CtcDecoder.decode(scores, 1, charset, true)
        assertEquals("X", unrestricted.text)
        assertEquals("2", restricted.text)
        assertTrue(restricted.confidence < 0.13)
    }

    @Test fun blankOnlyHasNoInventedConfidence() {
        val result = CtcDecoder.decode(logits(0, 0), 2, charset, true)
        assertEquals("", result.text)
        assertEquals(0.0, result.confidence, 0.0)
    }

    @Test fun largeLogitsUseStableSoftmax() {
        val scores = FloatArray(charset.size) { 10_000f }
        scores[1] = 10_100f
        val result = CtcDecoder.decode(scores, 1, charset)
        assertEquals("0", result.text)
        assertTrue(result.confidence.isFinite())
        assertEquals(1.0, result.confidence, 0.00001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteModelOutputIsRejected() {
        val scores = logits(1)
        scores[0] = Float.NaN
        CtcDecoder.decode(scores, 1, charset)
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongDimensionsAreRejected() {
        CtcDecoder.decode(logits(1), 2, charset)
    }
}
