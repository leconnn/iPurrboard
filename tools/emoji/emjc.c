/*
 * Decoder for Apple's "emjc" sbix images (the pixel stage; LZFSE is undone by the caller).
 * Written for tools/emoji/extract_apple_emoji.py; build with any C compiler, e.g.
 *   zig cc -shared -O2 -o emjc.dll tools/emoji/emjc.c      (or: gcc -shared -O2 -fPIC -o libemjc.so ...)
 *
 * Decompressed layout for a w x h image (format as documented by the emjc-decoder project):
 *   alpha[w*h], filter[h], residual[w*h*3] (interleaved, 3 per pixel), appendix[n].
 * Residuals are zigzag-coded (odd = negative) around a per-sample bias from the appendix, then
 * predicted per row: 0 none, 1 gradient-select (closer of left/up by the first sample),
 * 2 left, 3 up, 4 rounded average of left and up. The three samples are a luma-like base and two
 * colour differences, converted to BGRA below.
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#define EXPORT __declspec(dllexport)
#else
#define EXPORT __attribute__((visibility("default")))
#endif

static int32_t unzigzag(int32_t v, int32_t bias) {
    return (v & 1) ? -(v >> 1) - bias : (v >> 1) + bias;
}

/* Rounds toward zero, like C integer division of the sum. */
static int32_t half_round(int32_t a, int32_t b) {
    int32_t s = a + b + 1;
    return s < 0 ? -((-s) / 2) : s / 2;
}

static uint8_t to_byte(int32_t v) {
    int32_t m = v % 257;
    return (uint8_t) (m < 0 ? m + 257 : m);
}

/* Returns 0 on success; out receives w*h BGRA pixels. */
EXPORT int emjc_pixels(const uint8_t *data, size_t length, int w, int h, int appendix_len, uint8_t *out) {
    const size_t n = (size_t) w * h;
    if (length < n + (size_t) h + n * 3 + (size_t) appendix_len) return -1;
    const uint8_t *alpha = data, *filter = data + n, *res = data + n + h, *app = res + n * 3;
    int32_t *v = calloc(n * 3, sizeof(int32_t));
    if (!v) return -2;

    /* Appendix: each byte skips (byte / 4) samples, then sets a bias of 128 * (byte % 4). */
    size_t at = 0;
    for (int k = 0; k < appendix_len; k++) {
        at += app[k] / 4;
        if (at >= n * 3) break;
        v[at++] = 128 * (app[k] % 4);
    }

    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            size_t i = (size_t) y * w + x;
            int32_t *c = v + i * 3;
            for (int ch = 0; ch < 3; ch++) c[ch] = unzigzag(res[i * 3 + ch], c[ch]);
            const int32_t *left = x > 0 ? c - 3 : NULL;
            const int32_t *up = y > 0 ? c - (size_t) w * 3 : NULL;
            const int32_t *pred = NULL;
            switch (filter[y]) {
            case 1:
                if (left && up) {
                    const int32_t *ul = up - 3;
                    pred = abs(left[0] - ul[0]) < abs(up[0] - ul[0]) ? up : left;
                } else {
                    pred = left ? left : up;
                }
                break;
            case 2: pred = left; break;
            case 3: pred = up; break;
            case 4:
                if (left && up) {
                    for (int ch = 0; ch < 3; ch++) c[ch] += half_round(left[ch], up[ch]);
                } else {
                    pred = left ? left : up;
                }
                break;
            default: break;
            }
            if (pred) for (int ch = 0; ch < 3; ch++) c[ch] += pred[ch];

            /* base, p, q -> RGB; the rounding direction depends on the signs of p and q. */
            int32_t base = c[0], p = c[1], q = c[2];
            int32_t ph = p < 0 ? p / 2 : (p + 1) / 2;
            int32_t pl = p < 0 ? (p + 1) / 2 : p / 2;
            int32_t qh = q < 0 ? q / 2 : (q + 1) / 2;
            int32_t ql = q < 0 ? (q + 1) / 2 : q / 2;
            int32_t r = base + ph - ql;
            int32_t g = base + qh;
            int32_t b = base - pl - ql;
            uint8_t *o = out + i * 4;
            o[0] = to_byte(b);
            o[1] = to_byte(g);
            o[2] = to_byte(r);
            o[3] = alpha[i];
        }
    }
    free(v);
    return 0;
}
