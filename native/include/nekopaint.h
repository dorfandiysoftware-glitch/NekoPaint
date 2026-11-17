/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * NekoPaint portable drawing core (C).
 *
 * Plain C89-style code with no operating-system calls, so the same source builds for Android
 * (NDK), iOS, macOS, Linux, Windows (Vista+ and, with an old compiler, 95+), HarmonyOS (NDK).
 * Pixels are premultiplied ARGB packed as 0xAARRGGBB. The Java core in ../core is the reference:
 * results must match it bit for bit (see NativeParityTest).
 */
#ifndef NEKOPAINT_H
#define NEKOPAINT_H

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_MSC_VER) && _MSC_VER < 1600
typedef unsigned int np_u32;
#else
#include <stdint.h>
typedef uint32_t np_u32;
#endif

typedef struct np_brush {
    np_u32 rgb;      /* 0x00RRGGBB */
    float radius;    /* > 0 */
    float hardness;  /* 0..1 */
    float flow;      /* 0..1 */
    float spacing;   /* fraction of the diameter */
    int eraser;
} np_brush;

typedef struct np_stroke {
    int has_last;
    float last_x, last_y, last_p, carry;
    /* changed since the last take: minX, minY, maxX, maxY (inclusive); maxX < 0 means none */
    int pend[4];
    /* changed since the start of the stroke, same layout */
    int total[4];
} np_stroke;

void np_stroke_init(np_stroke *s);

/* Adds an input point and paints the part of the stroke up to it. */
void np_stroke_add_point(np_stroke *s, np_u32 *px, int width, int height,
                         const np_brush *b, float x, float y, float pressure);

typedef struct np_layer {
    const np_u32 *px;  /* width * height premultiplied ARGB */
    int opacity;       /* 1..255 */
} np_layer;

/*
 * Composites NORMAL-mode layers (bottom first) over a background into square-ish tiles.
 * tiles = x,y pairs (pixels) of ntiles tile origins. Tile k is written at out + k*tile*tile, packed
 * with the tile's real width (min(tile, width-x)). If rgba_bytes is set the words are stored as
 * 0xAABBGGRR, i.e. the byte order of Android ARGB_8888 bitmaps.
 */
void np_flatten_tiles(const np_layer *layers, int nlayers, int width, int height,
                      np_u32 background, const int *tiles, int ntiles, int tile,
                      np_u32 *out, int rgba_bytes);

/* Clears the pending rectangle (after the caller has read it). */
void np_stroke_reset_pending(np_stroke *s);

#ifdef __cplusplus
}
#endif
#endif
