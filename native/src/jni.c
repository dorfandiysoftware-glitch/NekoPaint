/* SPDX-License-Identifier: GPL-3.0-or-later */
/* JNI glue for nekopaint.core.NativeStroke (Android and desktop JVM). */
#include <jni.h>
#include <string.h>
#include "nekopaint.h"

/*
 * fs: float[4] = lastX, lastY, lastP, carry
 * is: int[9]   = hasLast, pend[4], total[4]
 */
JNIEXPORT void JNICALL Java_nekopaint_core_NativeStroke_addPoint(
    JNIEnv *env, jclass cls, jintArray pixels, jint width, jint height,
    jint rgb, jfloat radius, jfloat hardness, jfloat flow, jfloat spacing, jboolean eraser,
    jfloatArray fs, jintArray is, jfloat x, jfloat y, jfloat pressure)
{
    np_stroke st;
    np_brush br;
    jfloat f[4];
    jint i9[9];
    jint *px;
    (void)cls;

    (*env)->GetFloatArrayRegion(env, fs, 0, 4, f);
    (*env)->GetIntArrayRegion(env, is, 0, 9, i9);
    st.last_x = f[0]; st.last_y = f[1]; st.last_p = f[2]; st.carry = f[3];
    st.has_last = i9[0];
    memcpy(st.pend, i9 + 1, sizeof st.pend);
    memcpy(st.total, i9 + 5, sizeof st.total);
    br.rgb = (np_u32)rgb;
    br.radius = radius; br.hardness = hardness; br.flow = flow; br.spacing = spacing;
    br.eraser = eraser ? 1 : 0;

    px = (jint *)(*env)->GetPrimitiveArrayCritical(env, pixels, 0);
    if (!px) return;
    np_stroke_add_point(&st, (np_u32 *)px, width, height, &br, x, y, pressure);
    (*env)->ReleasePrimitiveArrayCritical(env, pixels, px, 0);

    f[0] = st.last_x; f[1] = st.last_y; f[2] = st.last_p; f[3] = st.carry;
    i9[0] = st.has_last;
    memcpy(i9 + 1, st.pend, sizeof st.pend);
    memcpy(i9 + 5, st.total, sizeof st.total);
    (*env)->SetFloatArrayRegion(env, fs, 0, 4, f);
    (*env)->SetIntArrayRegion(env, is, 0, 9, i9);
}

#include <stdlib.h>
#define NP_MAX_LAYERS 64

JNIEXPORT jboolean JNICALL Java_nekopaint_core_NativeStroke_flattenTiles(
    JNIEnv *env, jclass cls, jobjectArray layers, jintArray opacity, jint nlayers,
    jint width, jint height, jint background, jintArray tiles, jint ntiles, jint tile,
    jintArray out, jboolean rgba)
{
    np_layer l[NP_MAX_LAYERS];
    jintArray arrs[NP_MAX_LAYERS];
    jint *ptrs[NP_MAX_LAYERS];
    jint op[NP_MAX_LAYERS];
    jint *t, *o;
    int i;
    (void)cls;
    if (nlayers < 0 || nlayers > NP_MAX_LAYERS) return JNI_FALSE;
    (*env)->GetIntArrayRegion(env, opacity, 0, nlayers, op);
    for (i = 0; i < nlayers; i++) arrs[i] = (jintArray)(*env)->GetObjectArrayElement(env, layers, i);
    for (i = 0; i < nlayers; i++) {
        ptrs[i] = (jint *)(*env)->GetPrimitiveArrayCritical(env, arrs[i], 0);
        l[i].px = (const np_u32 *)ptrs[i];
        l[i].opacity = op[i];
    }
    t = (jint *)(*env)->GetPrimitiveArrayCritical(env, tiles, 0);
    o = (jint *)(*env)->GetPrimitiveArrayCritical(env, out, 0);
    np_flatten_tiles(l, nlayers, width, height, (np_u32)background, (const int *)t, ntiles, tile,
                     (np_u32 *)o, rgba ? 1 : 0);
    (*env)->ReleasePrimitiveArrayCritical(env, out, o, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, tiles, t, JNI_ABORT);
    for (i = nlayers - 1; i >= 0; i--) (*env)->ReleasePrimitiveArrayCritical(env, arrs[i], ptrs[i], JNI_ABORT);
    for (i = 0; i < nlayers; i++) (*env)->DeleteLocalRef(env, arrs[i]);
    return JNI_TRUE;
}
