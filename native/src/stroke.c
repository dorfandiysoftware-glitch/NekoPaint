/* SPDX-License-Identifier: GPL-3.0-or-later */
#include <math.h>
#include <stddef.h>
#include "nekopaint.h"

#define MAX_COORD 100000.0f

/* Same as Java's Math.round(float) for the values used here. */
static int np_round(float x)
{
    float f = floorf(x);
    int r = (int)f;
    return (x - f >= 0.5f) ? r + 1 : r;
}

/* round(x / 255) for 0..65025, no division. */
static unsigned div255(unsigned x)
{
    unsigned t = x + 128u;
    return (t + (t >> 8)) >> 8;
}

static np_u32 premultiply(np_u32 argb)
{
    unsigned a = argb >> 24;
    if (a == 255u) return argb;
    if (a == 0u) return 0u;
    return (a << 24)
         | (div255(((argb >> 16) & 0xFFu) * a) << 16)
         | (div255(((argb >> 8) & 0xFFu) * a) << 8)
         | div255((argb & 0xFFu) * a);
}

/* Two channels at a time in one 32-bit word: round(x/255) per 16-bit lane, x <= 65025. */
static np_u32 lanes255(np_u32 t)
{
    t += 0x00800080u;
    return ((t + ((t >> 8) & 0x00FF00FFu)) >> 8) & 0x00FF00FFu;
}

/* source over destination, both valid premultiplied; the source alpha is 1..254 */
static np_u32 over(np_u32 src, np_u32 dst, unsigned sa)
{
    unsigned inv = 255u - sa;
    np_u32 rb = lanes255((dst & 0x00FF00FFu) * inv);
    np_u32 ag = lanes255(((dst >> 8) & 0x00FF00FFu) * inv);
    return src + (rb | (ag << 8));
}

/* multiplies every channel by factor/255 (factor 1..254) */
static np_u32 scale(np_u32 p, unsigned f)
{
    np_u32 rb = lanes255((p & 0x00FF00FFu) * f);
    np_u32 ag = lanes255(((p >> 8) & 0x00FF00FFu) * f);
    return rb | (ag << 8);
}

static void mark(int *r, int x, int y)
{
    if (x < r[0]) r[0] = x;
    if (y < r[1]) r[1] = y;
    if (x > r[2]) r[2] = x;
    if (y > r[3]) r[3] = y;
}

static int imin(int a, int b) { return a < b ? a : b; }
static int imax(int a, int b) { return a > b ? a : b; }

typedef struct job {
    np_u32 *px;
    int width, x0, x1, y0, y1;
    float cx, cy, r, fall, inner, strength, rsq, innersq;
    int full_alpha, erase;
    np_u32 full_source, rgb;
    /* per worker output */
    int stride, first_row;
    int pend[4];
} job;

/* Paints rows y0+first_row, y0+first_row+stride, ... of the stamp. */
static void run_rows(job *j)
{
    int y;
    int width = j->width;
    j->pend[0] = 0x7FFFFFFF; j->pend[1] = 0x7FFFFFFF; j->pend[2] = -1; j->pend[3] = -1;
    for (y = j->y0 + j->first_row; y <= j->y1; y += j->stride) {
        float dy = (float)y + 0.5f - j->cy;
        float rest = j->rsq - dy * dy;
        float half, core_rest;
        int xa, xb, core_a = 1, core_b = 0, x, first = -1, last = -1;
        np_u32 *row;
        if (rest <= 0.0f) continue;
        half = sqrtf(rest);
        xa = imax(j->x0, (int)floorf(j->cx - half - 0.5f));
        xb = imin(j->x1, (int)ceilf(j->cx + half - 0.5f));
        core_rest = j->innersq - dy * dy;
        if (core_rest > 0.0f) {
            float core_half = sqrtf(core_rest);
            core_a = (int)ceilf(j->cx - core_half - 0.5f);
            core_b = (int)floorf(j->cx + core_half - 0.5f);
        }
        row = j->px + (size_t)y * (size_t)width;
        for (x = xa; x <= xb; x++) {
            int a;
            np_u32 source, old, result;
            if (x >= core_a && x <= core_b) {
                a = j->full_alpha;
                source = j->full_source;
            } else {
                float dx = (float)x + 0.5f - j->cx;
                float d = sqrtf(dx * dx + dy * dy);
                float coverage;
                if (d >= j->r) continue;
                coverage = d <= j->inner ? 1.0f : (j->r - d) / j->fall;
                a = np_round(coverage * j->strength * 255.0f);
                source = j->erase ? 0u : premultiply(((np_u32)(a > 0 ? a : 0) << 24) | j->rgb);
            }
            if (a <= 0) continue;
            old = row[x];
            if (j->erase) {
                result = (a >= 255) ? 0u : scale(old, (unsigned)(255 - a));
            } else if (a >= 255) {
                result = source;
            } else {
                result = over(source, old, (unsigned)a);
            }
            if (result != old) {
                row[x] = result;
                if (first < 0) first = x;
                last = x;
            }
        }
        if (first >= 0) {
            mark(j->pend, first, y);
            mark(j->pend, last, y);
        }
    }
}

#ifdef NP_THREADS
#include <pthread.h>
#include <unistd.h>
#define NP_MAX_THREADS 4
#define NP_THREAD_MIN_AREA 60000

static void *worker(void *arg)
{
    run_rows((job *)arg);
    return 0;
}

static int thread_count(void)
{
    static int n = 0;
    if (n == 0) {
        long c = sysconf(_SC_NPROCESSORS_ONLN);
        n = c < 1 ? 1 : (c > NP_MAX_THREADS ? NP_MAX_THREADS : (int)c);
    }
    return n;
}
#endif

static void merge(int *dst, const int *src)
{
    if (src[2] < 0) return;
    if (src[0] < dst[0]) dst[0] = src[0];
    if (src[1] < dst[1]) dst[1] = src[1];
    if (src[2] > dst[2]) dst[2] = src[2];
    if (src[3] > dst[3]) dst[3] = src[3];
}

static void stamp(np_stroke *s, np_u32 *px, int width, int height, const np_brush *b,
                  float cx, float cy, float pressure)
{
    job j;
    float r = b->radius;
    float strength;
    int x0 = imax(0, (int)floorf(cx - r));
    int x1 = imin(width - 1, (int)ceilf(cx + r));
    int y0 = imax(0, (int)floorf(cy - r));
    int y1 = imin(height - 1, (int)ceilf(cy + r));
    int nthreads = 1;

    if (x0 > x1 || y0 > y1) return;
    j.px = px; j.width = width; j.x0 = x0; j.x1 = x1; j.y0 = y0; j.y1 = y1;
    j.cx = cx; j.cy = cy; j.r = r;
    j.fall = r * (1.0f - b->hardness);
    if (j.fall < 1.0f) j.fall = 1.0f;
    j.inner = r - j.fall;
    strength = b->flow * pressure;
    j.strength = strength;
    j.erase = b->eraser;
    j.rsq = r * r;
    j.innersq = j.inner > 0.0f ? j.inner * j.inner : -1.0f;
    j.full_alpha = np_round(strength * 255.0f);
    j.rgb = b->rgb & 0xFFFFFFu;
    j.full_source = j.erase ? 0u : premultiply(((np_u32)j.full_alpha << 24) | j.rgb);
    j.stride = 1; j.first_row = 0;

#ifdef NP_THREADS
    if ((long)(x1 - x0 + 1) * (long)(y1 - y0 + 1) >= NP_THREAD_MIN_AREA) {
        nthreads = thread_count();
    }
    if (nthreads > 1) {
        job jobs[NP_MAX_THREADS];
        pthread_t th[NP_MAX_THREADS];
        int started[NP_MAX_THREADS];
        int i;
        for (i = 0; i < nthreads; i++) {
            jobs[i] = j;
            jobs[i].stride = nthreads;
            jobs[i].first_row = i;
        }
        for (i = 1; i < nthreads; i++) {
            started[i] = pthread_create(&th[i], 0, worker, &jobs[i]) == 0;
            if (!started[i]) run_rows(&jobs[i]); /* could not start: do it here */
        }
        run_rows(&jobs[0]);
        for (i = 1; i < nthreads; i++) {
            if (started[i]) pthread_join(th[i], 0);
        }
        for (i = 0; i < nthreads; i++) {
            merge(s->pend, jobs[i].pend);
            merge(s->total, jobs[i].pend);
        }
        return;
    }
#endif
    (void)nthreads;
    run_rows(&j);
    merge(s->pend, j.pend);
    merge(s->total, j.pend);
}

void np_stroke_init(np_stroke *s)
{
    s->has_last = 0;
    s->last_x = s->last_y = s->last_p = s->carry = 0.0f;
    s->pend[0] = s->total[0] = 0x7FFFFFFF;
    s->pend[1] = s->total[1] = 0x7FFFFFFF;
    s->pend[2] = s->total[2] = -1;
    s->pend[3] = s->total[3] = -1;
}

void np_stroke_reset_pending(np_stroke *s)
{
    s->pend[0] = 0x7FFFFFFF;
    s->pend[1] = 0x7FFFFFFF;
    s->pend[2] = -1;
    s->pend[3] = -1;
}

void np_stroke_add_point(np_stroke *s, np_u32 *px, int width, int height,
                         const np_brush *b, float x, float y, float pressure)
{
    float p, step, dx, dy, dist;
    if (x != x || y != y || x - x != 0.0f || y - y != 0.0f) return; /* NaN or infinite */
    if (x > MAX_COORD) x = MAX_COORD;
    if (x < -MAX_COORD) x = -MAX_COORD;
    if (y > MAX_COORD) y = MAX_COORD;
    if (y < -MAX_COORD) y = -MAX_COORD;
    if (pressure != pressure) p = 0.0f;
    else p = pressure < 0.0f ? 0.0f : (pressure > 1.0f ? 1.0f : pressure);
    step = b->spacing * 2.0f * b->radius;
    if (step < 0.5f) step = 0.5f;

    if (!s->has_last) {
        stamp(s, px, width, height, b, x, y, p);
        s->has_last = 1;
        s->last_x = x;
        s->last_y = y;
        s->last_p = p;
        s->carry = step;
        return;
    }
    dx = x - s->last_x;
    dy = y - s->last_y;
    dist = sqrtf(dx * dx + dy * dy);
    if (dist > 0.0f) {
        float pos = s->carry;
        while (pos <= dist) {
            float t = pos / dist;
            stamp(s, px, width, height, b, s->last_x + dx * t, s->last_y + dy * t,
                  s->last_p + (p - s->last_p) * t);
            pos += step;
        }
        s->carry = pos - dist;
    }
    s->last_x = x;
    s->last_y = y;
    s->last_p = p;
}

/* ------------------------------------------------------------------ flatten */

typedef struct fjob {
    const np_layer *layers;
    int nlayers, width, height, ntiles, tile, rgba, first, stride;
    np_u32 bg;
    const int *tiles;
    np_u32 *out;
} fjob;

static np_u32 swap_rb(np_u32 p)
{
    return (p & 0xFF00FF00u) | ((p >> 16) & 0xFFu) | ((p & 0xFFu) << 16);
}

static void flatten_one(const fjob *f, int k)
{
    int x0 = f->tiles[2 * k], y0 = f->tiles[2 * k + 1];
    int w = imin(f->tile, f->width - x0), h = imin(f->tile, f->height - y0);
    np_u32 *o = f->out + (size_t)k * (size_t)f->tile * (size_t)f->tile;
    int y, x, i;
    if (w <= 0 || h <= 0) return;
    for (y = 0; y < h; y++) {
        np_u32 *dst = o + (size_t)y * (size_t)w;
        for (x = 0; x < w; x++) dst[x] = f->bg;
        for (i = 0; i < f->nlayers; i++) {
            const np_u32 *src = f->layers[i].px + (size_t)(y0 + y) * (size_t)f->width + x0;
            unsigned op = (unsigned)f->layers[i].opacity;
            for (x = 0; x < w; x++) {
                np_u32 sp = src[x];
                unsigned sa;
                if (sp == 0u) continue;
                if (op != 255u) {
                    sp = scale(sp, op);
                    if ((sp >> 24) == 0u) continue;
                }
                sa = sp >> 24;
                dst[x] = sa == 255u ? sp : over(sp, dst[x], sa);
            }
        }
        if (f->rgba) {
            for (x = 0; x < w; x++) dst[x] = swap_rb(dst[x]);
        }
    }
}

static void flatten_range(const fjob *f)
{
    int k;
    for (k = f->first; k < f->ntiles; k += f->stride) flatten_one(f, k);
}

#ifdef NP_THREADS
static void *fworker(void *arg)
{
    flatten_range((const fjob *)arg);
    return 0;
}
#endif

void np_flatten_tiles(const np_layer *layers, int nlayers, int width, int height,
                      np_u32 background, const int *tiles, int ntiles, int tile,
                      np_u32 *out, int rgba_bytes)
{
    fjob f;
    f.layers = layers; f.nlayers = nlayers; f.width = width; f.height = height;
    f.ntiles = ntiles; f.tile = tile; f.rgba = rgba_bytes; f.bg = background;
    f.tiles = tiles; f.out = out; f.first = 0; f.stride = 1;
    f.bg = background;
#ifdef NP_THREADS
    if (ntiles >= 4) {
        int n = thread_count();
        if (n > 1) {
            fjob jobs[NP_MAX_THREADS];
            pthread_t th[NP_MAX_THREADS];
            int started[NP_MAX_THREADS];
            int i;
            for (i = 0; i < n; i++) {
                jobs[i] = f;
                jobs[i].first = i;
                jobs[i].stride = n;
            }
            for (i = 1; i < n; i++) {
                started[i] = pthread_create(&th[i], 0, fworker, &jobs[i]) == 0;
                if (!started[i]) flatten_range(&jobs[i]);
            }
            flatten_range(&jobs[0]);
            for (i = 1; i < n; i++) if (started[i]) pthread_join(th[i], 0);
            return;
        }
    }
#endif
    flatten_range(&f);
}
