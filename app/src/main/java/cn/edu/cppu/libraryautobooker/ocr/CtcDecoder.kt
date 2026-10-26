package cn.edu.cppu.libraryautobooker.ocr

import kotlin.math.exp

/** Greedy CTC decoding. Scores are a model signal, not a correctness guarantee. */
internal object CtcDecoder {
    data class Decoded(val text: String, val confidence: Double)

    fun decode(
        scores: FloatArray,
        steps: Int,
        charset: List<String>,
        digitsOnly: Boolean = false
    ): Decoded {
        require(steps > 0 && charset.size > 1 && charset[0].isEmpty())
        require(scores.size.toLong() == steps.toLong() * charset.size)
        require(scores.all { it.isFinite() }) { "模型输出包含无效数值" }
        val allowed = if (digitsOnly) charset.indices.filter {
            it == 0 || (charset[it].length == 1 && charset[it][0] in '0'..'9')
        }.toIntArray() else charset.indices.toList().toIntArray()
        require(!digitsOnly || allowed.size == 11) { "字符表不包含完整数字集" }

        val text = StringBuilder()
        val confidences = mutableListOf<Double>()
        var previous = 0
        repeat(steps) { step ->
            val start = step * charset.size
            var selected = allowed[0]
            for (index in allowed) {
                if (scores[start + index] > scores[start + selected]) selected = index
            }
            if (selected != previous && selected != 0) {
                text.append(charset[selected])
                // Use the entire alphabet in the denominator, including blank.
                // Restricting the alphabet must not manufacture confidence.
                var maximum = Double.NEGATIVE_INFINITY
                for (index in charset.indices) maximum = maxOf(maximum, scores[start + index].toDouble())
                var denominator = 0.0
                for (index in charset.indices) denominator += exp(scores[start + index] - maximum)
                confidences += exp(scores[start + selected] - maximum) / denominator
            }
            // Always track the actual index (not the restricted list position).
            // A blank between equal digits allows e.g. 1, blank, 1 -> "11".
            previous = selected
        }
        return Decoded(text.toString(), confidences.minOrNull() ?: 0.0)
    }
}
