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
