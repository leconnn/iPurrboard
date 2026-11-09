package dev.leconnn.ioskeyboard.text

import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Slide-to-type decoder (SHARK2-style template matching).
 *
 * Points are in [KeyProximity]'s frame: layout points with row centres at y = 0, 54, 108. Each
 * candidate word's template is the polyline through its letters' key centres (repeats collapsed).
 * Both paths are resampled to [N] points; a word is scored by how close the paths are in place
 * (location) and in form after normalising position and size (shape), how close the path's ends
 * are to the word's first and last keys (people aim those carefully), plus its frequency.
 * Only words whose first and last keys are near the path's ends are considered.
 */
class SwipeDecoder(private val dict: Dictionary) {

    data class Result(val word: String, val score: Double)

    /** Lowercase words never returned (Block Offensive Words). */
    @Volatile var blocked: Set<String> = emptySet()

    /** [shape] is [points] normalised, computed once per word rather than on every swipe. */
    private class Template(val index: Int, val points: FloatArray, val shape: FloatArray, val length: Float)

    /** Word indices grouped by their (first letter, last letter) keys. */
    private val byEnds = HashMap<Int, IntArrayList>()
    private val templates = HashMap<Int, Template>()

    init {
        for (i in 0 until dict.size) {
            val letters = letters(dict.keys[i]) ?: continue
            if (letters.length < 2) continue
            byEnds.getOrPut(endsKey(letters.first(), letters.last())) { IntArrayList() }.add(i)
        }
    }

    /** Best matches for a path, best first. */
    fun decode(path: List<Pair<Float, Float>>, limit: Int = 3): List<Result> {
        if (path.size < 2) return emptyList()
        val input = resample(path)
        val inputLength = length(path)
        val start = path.first()
        val end = path.last()
        val firsts = nearKeys(start)
        val lasts = nearKeys(end)
        val inputShape = normalise(input)
        val results = ArrayList<Result>()
        for (f in firsts) for (l in lasts) {
            val ids = byEnds[endsKey(f, l)] ?: continue
            for (k in 0 until ids.size) {
                val t = template(ids[k]) ?: continue
                // Very different lengths can't be the same gesture.
                if (t.length > inputLength * 2.2f + PITCH || t.length * 2.2f + PITCH < inputLength) continue
                val loc = meanDistance(input, t.points)
                val shape = meanDistance(inputShape, t.shape)
                val ends = (hypot(input[0] - t.points[0], input[1] - t.points[1]) +
                    hypot(input[2 * N - 2] - t.points[2 * N - 2], input[2 * N - 1] - t.points[2 * N - 1])) / PITCH
                val score = ln(dict.freq[t.index] + 1.0) - LOCATION_WEIGHT * loc - SHAPE_WEIGHT * shape - END_WEIGHT * ends
                results.add(Result(dict.words[t.index], score))
            }
        }
        return results.sortedByDescending { it.score }.distinctBy { it.word.lowercase() }
            .filter { blocked.isEmpty() || it.word.lowercase() !in blocked }.take(limit)
    }

    private fun template(i: Int): Template? = templates.getOrPut(i) {
        val letters = letters(dict.keys[i]) ?: return null
        val pts = letters.mapNotNull { KeyProximity.centre(it) }
        val points = resample(pts)
        Template(i, points, normalise(points), length(pts))
    }

    /** Lowercase letters with repeats collapsed and apostrophes dropped, or null if not a-z. */
    private fun letters(key: String): String? {
        val sb = StringBuilder()
        for (c in key) {
            if (c == '\'') continue
            if (c !in 'a'..'z') return null
            if (sb.isEmpty() || sb.last() != c) sb.append(c)
        }
        return sb.toString()
    }

    private fun nearKeys(p: Pair<Float, Float>): List<Char> = ('a'..'z').filter { c ->
        val k = KeyProximity.centre(c) ?: return@filter false
        hypot(k.first - p.first, k.second - p.second) <= END_RADIUS
    }

    private fun endsKey(a: Char, b: Char) = (a - 'a') * 26 + (b - 'a')

    private class IntArrayList {
        var data = IntArray(8)
        var size = 0
        fun add(v: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        operator fun get(i: Int) = data[i]
    }

    companion object {
        const val N = 50
        const val PITCH = 37.5f
        /** How far from a key centre the path may start or end (1.5 key pitches). */
        const val END_RADIUS = 1.5f * PITCH
        const val LOCATION_WEIGHT = 0.12
        const val SHAPE_WEIGHT = 12.0
        /** Per key pitch of distance between the path's ends and the word's first and last keys. */
        const val END_WEIGHT = 1.5

        fun length(pts: List<Pair<Float, Float>>): Float {
            var d = 0f
            for (i in 1 until pts.size) d += hypot(pts[i].first - pts[i - 1].first, pts[i].second - pts[i - 1].second)
            return d
        }

        /** [N] points evenly spaced along the polyline, as x0, y0, x1, y1, ... */
        fun resample(pts: List<Pair<Float, Float>>): FloatArray {
            val out = FloatArray(N * 2)
            val total = length(pts)
            if (total == 0f || pts.size == 1) {
                for (i in 0 until N) { out[2 * i] = pts[0].first; out[2 * i + 1] = pts[0].second }
                return out
            }
            val step = total / (N - 1)
            var seg = 1
            var segStart = 0f
            for (i in 0 until N) {
                val target = minOf(i * step, total)
                while (seg < pts.size - 1) {
                    val len = hypot(pts[seg].first - pts[seg - 1].first, pts[seg].second - pts[seg - 1].second)
                    if (segStart + len >= target) break
                    segStart += len
                    seg++
                }
                val a = pts[seg - 1]
                val b = pts[seg]
                val len = hypot(b.first - a.first, b.second - a.second)
                val t = if (len == 0f) 0f else ((target - segStart) / len).coerceIn(0f, 1f)
                out[2 * i] = a.first + (b.first - a.first) * t
                out[2 * i + 1] = a.second + (b.second - a.second) * t
            }
            return out
        }

        /** Centred on the centroid and scaled so the larger bounding-box side is 1. */
        fun normalise(p: FloatArray): FloatArray {
            var cx = 0f; var cy = 0f
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (i in 0 until N) {
                val x = p[2 * i]; val y = p[2 * i + 1]
                cx += x; cy += y
                minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
            }
            cx /= N; cy /= N
            val s = max(max(maxX - minX, maxY - minY), 1f)
            return FloatArray(N * 2) { i -> if (i % 2 == 0) (p[i] - cx) / s else (p[i] - cy) / s }
        }

        fun meanDistance(a: FloatArray, b: FloatArray): Float {
            var d = 0f
            for (i in 0 until N) {
                val dx = a[2 * i] - b[2 * i]
                val dy = a[2 * i + 1] - b[2 * i + 1]
                d += sqrt(dx * dx + dy * dy)
            }
            return d / N
        }
    }
}
