package dev.leconnn.ioskeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Synthetic swipes: each word's key-centre path, densified, with Gaussian noise. */
class SwipeDecoderTest {
    companion object {
        lateinit var dict: Dictionary
        lateinit var decoder: SwipeDecoder

        @BeforeClass @JvmStatic fun load() {
            val f = listOf("src/main/assets/dict/en.tsv", "app/src/main/assets/dict/en.tsv").map(::File).first { it.exists() }
            dict = Dictionary.parse(f.readLines(Charsets.UTF_8).asSequence())
            decoder = SwipeDecoder(dict)
        }

        /** The 100 most frequent dictionary words of 2+ letters (without apostrophes). */
        val common: List<String> by lazy {
            (0 until dict.size).filter { dict.keys[it].length >= 2 && dict.keys[it].all { c -> c in 'a'..'z' } }
                .sortedByDescending { dict.freq[it] }.take(100).map { dict.keys[it] }
        }
    }

    private fun swipe(word: String, rnd: Random, sigma: Float): List<Pair<Float, Float>> {
        val keys = word.mapNotNull { KeyProximity.centre(it) }
        val out = ArrayList<Pair<Float, Float>>()
        fun noise() = (rnd.nextDouble().let { u1 -> rnd.nextDouble().let { u2 ->
            kotlin.math.sqrt(-2 * kotlin.math.ln(u1 + 1e-12)) * kotlin.math.cos(2 * Math.PI * u2) } } * sigma).toFloat()
        for (i in 0 until keys.size - 1) {
            val (ax, ay) = keys[i]
            val (bx, by) = keys[i + 1]
            for (s in 0 until 8) {
                val t = s / 8f
                out.add(ax + (bx - ax) * t + noise() to ay + (by - ay) * t + noise())
            }
        }
        out.add(keys.last().first + noise() to keys.last().second + noise())
        return out
    }

    private fun accuracy(sigma: Float, seed: Int): Pair<Double, Double> {
        val rnd = Random(seed)
        var top1 = 0
        var top3 = 0
        for (w in common) {
            val r = decoder.decode(swipe(w, rnd, sigma)).map { it.word.lowercase() }
            if (r.firstOrNull() == w) top1++
            if (w in r) top3++
        }
        return top1.toDouble() / common.size to top3.toDouble() / common.size
    }

    /** Clean paths through the key centres must decode to the word, even against far more common ones. */
    @Test fun cleanPaths() {
        for (w in listOf("hello", "typing", "keyboard", "phone", "world")) {
            assertEquals(w, decoder.decode(swipe(w, Random(1), 0f)).first().word)
        }
    }

    @Test fun noisyAccuracy() {
        val (top1, top3) = accuracy(6f, 42)
        println("swipe accuracy sigma 6: top1=$top1 top3=$top3")
        assertTrue("top-1 $top1", top1 >= 0.90)
        assertTrue("top-3 $top3", top3 >= 0.97)
    }

    @Test fun speed() {
        val path = swipe("something", Random(3), 6f)
        decoder.decode(path)
        val start = System.nanoTime()
        repeat(10) { decoder.decode(path) }
        val ms = (System.nanoTime() - start) / 10 / 1_000_000
        assertTrue("decode took $ms ms", ms < 30)
    }
}
