// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.view.MotionEvent;

/**
 * Moves, scales, rotates and bends a picture (a layer's contents or an imported image) before it
 * is put on a layer. Three ways to shape it, like the tabs of the panel: move/scale, perspective
 * and mesh. Everything is in canvas pixels; the caller supplies the screen matrix.
 */
final class TransformTool {

    static final int MODE_SCALE = 0;
    static final int MODE_PERSPECTIVE = 1;
    static final int MODE_MESH = 2;

    /** Told when the shape changed, so the panel can move its sliders. */
    interface Listener {
        void onChanged();
    }

    private static final int DRAG_NONE = 0;
    private static final int DRAG_MOVE = 1;
    private static final int DRAG_CORNER = 2;
    private static final int DRAG_ROTATE = 3;
    private static final int DRAG_POINT = 4;
    private static final int DRAG_EDGE = 5;

    private final Bitmap src;
    private final int docW;
    private final int docH;
    private final float cx0;
    private final float cy0;
    private final float sw;
    private final float sh;
    private Listener listener;

    int mode = MODE_SCALE;
    boolean showFrame = true;
    boolean smoothFilter = false; // false: nearest neighbour, true: bilinear

    // Move / scale
    float tx;
    float ty;
    float zoom = 1f;
    float angle; // degrees

    // Perspective
    final float[] pc = new float[8];
    boolean perspective = true;

    // Mesh
    int divX = 3;
    int divY = 3;
    int smoothness = 100;
    float[] mesh;

    private int drag = DRAG_NONE;
    private int dragIndex;
    private float lastX;
    private float lastY;
    private boolean twoFingers;
    private float baseDist;
    private float baseAngle;
    private float baseZoom;
    private float baseParamAngle;

    /**
     * @param src    the picture to shape
     * @param left   where its top-left corner starts on the canvas
     * @param top    same, vertical
     */
    TransformTool(Bitmap src, float left, float top, int docW, int docH) {
        this.src = src;
        this.docW = docW;
        this.docH = docH;
        this.sw = src.getWidth();
        this.sh = src.getHeight();
        this.cx0 = left + sw / 2f;
        this.cy0 = top + sh / 2f;
    }

    void setListener(Listener l) {
        listener = l;
    }

    int getDocWidth() {
        return docW;
    }

    int getDocHeight() {
        return docH;
    }

    Bitmap getSource() {
        return src;
    }

    private void changed() {
        if (listener != null) {
            listener.onChanged();
        }
    }

    // ------------------------------------------------------------------ geometry

    /** The four corners (top-left, top-right, bottom-right, bottom-left) for move/scale. */
    private float[] scaleQuad() {
        double a = Math.toRadians(angle);
        float cos = (float) Math.cos(a);
        float sin = (float) Math.sin(a);
        float hw = sw / 2f * zoom;
        float hh = sh / 2f * zoom;
        float[] dx = {-hw, hw, hw, -hw};
        float[] dy = {-hh, -hh, hh, hh};
        float[] q = new float[8];
        for (int i = 0; i < 4; i++) {
            q[2 * i] = cx0 + tx + dx[i] * cos - dy[i] * sin;
            q[2 * i + 1] = cy0 + ty + dx[i] * sin + dy[i] * cos;
        }
        return q;
    }

    /** The outline of the picture in whichever tab is active. */
    float[] currentQuad() {
        if (mode == MODE_PERSPECTIVE) {
            return pc.clone();
        }
        if (mode == MODE_MESH && mesh != null) {
            int w = divX + 1;
            int h = divY + 1;
            return new float[] {
                mesh[0], mesh[1],
                mesh[2 * (w - 1)], mesh[2 * (w - 1) + 1],
                mesh[2 * (h * w - 1)], mesh[2 * (h * w - 1) + 1],
                mesh[2 * (h - 1) * w], mesh[2 * (h - 1) * w + 1]};
        }
        return scaleQuad();
    }

    void setMode(int newMode) {
        if (newMode == mode) {
            return;
        }
        float[] q = currentQuad();
        mode = newMode;
        if (newMode == MODE_PERSPECTIVE) {
            System.arraycopy(q, 0, pc, 0, 8);
        } else if (newMode == MODE_MESH) {
            resetMesh(q);
        }
        changed();
    }

    void setDivisions(int x, int y) {
        float[] q = currentQuad();
        divX = Math.max(1, Math.min(10, x));
        divY = Math.max(1, Math.min(10, y));
        resetMesh(q);
        changed();
    }

    private void resetMesh(float[] q) {
        int w = divX + 1;
        int h = divY + 1;
        mesh = new float[w * h * 2];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                float u = i / (float) divX;
                float v = j / (float) divY;
                float[] p = bilinear(q, u, v);
                mesh[2 * (j * w + i)] = p[0];
                mesh[2 * (j * w + i) + 1] = p[1];
            }
        }
    }

    private static float[] bilinear(float[] q, float u, float v) {
        float tx0 = q[0] + (q[2] - q[0]) * u;
        float ty0 = q[1] + (q[3] - q[1]) * u;
        float bx0 = q[6] + (q[4] - q[6]) * u;
        float by0 = q[7] + (q[5] - q[7]) * u;
        return new float[] {tx0 + (bx0 - tx0) * v, ty0 + (by0 - ty0) * v};
    }

    // ------------------------------------------------------------------ drawing

    /** Draws the shaped picture; the canvas must already have the canvas-to-screen matrix. */
    void render(Canvas c) {
        Paint p = new Paint();
        p.setFilterBitmap(smoothFilter);
        int mw;
        int mh;
        float[] v;
        if (mode == MODE_SCALE) {
            float[] q = scaleQuad();
            mw = 1;
            mh = 1;
            v = new float[] {q[0], q[1], q[2], q[3], q[6], q[7], q[4], q[5]};
        } else if (mode == MODE_PERSPECTIVE) {
            mw = 16;
            mh = 16;
            v = new float[(mw + 1) * (mh + 1) * 2];
            float[] h = perspective ? homography(pc) : null;
            for (int j = 0; j <= mh; j++) {
                for (int i = 0; i <= mw; i++) {
                    float u = i / (float) mw;
                    float t = j / (float) mh;
                    float[] pt = h != null ? project(h, u, t) : bilinear(pc, u, t);
                    v[2 * (j * (mw + 1) + i)] = pt[0];
                    v[2 * (j * (mw + 1) + i) + 1] = pt[1];
                }
            }
        } else {
            int r = smoothness > 0 ? 6 : 1;
            mw = divX * r;
            mh = divY * r;
            v = new float[(mw + 1) * (mh + 1) * 2];
            for (int j = 0; j <= mh; j++) {
                for (int i = 0; i <= mw; i++) {
                    float[] pt = meshPoint(i / (float) r, j / (float) r);
                    v[2 * (j * (mw + 1) + i)] = pt[0];
                    v[2 * (j * (mw + 1) + i) + 1] = pt[1];
                }
            }
        }
        c.drawBitmapMesh(src, mw, mh, v, 0, null, 0, p);
    }

    /** @return the shaped picture on a transparent canvas-sized bitmap, as non-premultiplied ARGB */
    int[] renderPixels() {
        Bitmap out = Bitmap.createBitmap(docW, docH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        render(c);
        int[] px = new int[docW * docH];
        out.getPixels(px, 0, docW, 0, 0, docW, docH);
        out.recycle();
        return px;
    }

    /** Position on the mesh at grid coordinates (gx, gy), blending linear and spline shapes. */
    private float[] meshPoint(float gx, float gy) {
        int w = divX + 1;
        int i = Math.min(divX - 1, (int) Math.floor(gx));
        int j = Math.min(divY - 1, (int) Math.floor(gy));
        float fx = gx - i;
        float fy = gy - j;
        float[] lin = new float[2];
        float[] a = meshAt(i, j);
        float[] b = meshAt(i + 1, j);
        float[] c = meshAt(i, j + 1);
        float[] d = meshAt(i + 1, j + 1);
        for (int k = 0; k < 2; k++) {
            float top = a[k] + (b[k] - a[k]) * fx;
            float bottom = c[k] + (d[k] - c[k]) * fx;
            lin[k] = top + (bottom - top) * fy;
        }
        if (smoothness <= 0) {
            return lin;
        }
        float[][] rows = new float[4][2];
        for (int r = 0; r < 4; r++) {
            int jj = j - 1 + r;
            for (int k = 0; k < 2; k++) {
                rows[r][k] = spline(meshAt(i - 1, jj)[k], meshAt(i, jj)[k], meshAt(i + 1, jj)[k],
                        meshAt(i + 2, jj)[k], fx);
            }
        }
        float[] sp = new float[2];
        for (int k = 0; k < 2; k++) {
            sp[k] = spline(rows[0][k], rows[1][k], rows[2][k], rows[3][k], fy);
        }
        float m = smoothness / 100f;
        return new float[] {lin[0] + (sp[0] - lin[0]) * m, lin[1] + (sp[1] - lin[1]) * m};
    }

    private float[] meshAt(int i, int j) {
        int w = divX + 1;
        i = Math.max(0, Math.min(divX, i));
        j = Math.max(0, Math.min(divY, j));
        return new float[] {mesh[2 * (j * w + i)], mesh[2 * (j * w + i) + 1]};
    }

    private static float spline(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return 0.5f * ((2f * p1) + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2
                + (-p0 + 3f * p1 - 3f * p2 + p3) * t3);
    }

    /** Maps the unit square to the quad q (projectively). Returns 8 numbers for {@link #project}. */
    private static float[] homography(float[] q) {
        float x0 = q[0], y0 = q[1], x1 = q[2], y1 = q[3], x2 = q[4], y2 = q[5], x3 = q[6], y3 = q[7];
        float dx1 = x1 - x2;
        float dx2 = x3 - x2;
        float dx3 = x0 - x1 + x2 - x3;
        float dy1 = y1 - y2;
        float dy2 = y3 - y2;
        float dy3 = y0 - y1 + y2 - y3;
        float g;
        float h;
        float det = dx1 * dy2 - dx2 * dy1;
        if (Math.abs(det) < 1e-6f) {
            g = 0;
            h = 0;
        } else {
            g = (dx3 * dy2 - dx2 * dy3) / det;
            h = (dx1 * dy3 - dx3 * dy1) / det;
        }
        float a = x1 - x0 + g * x1;
        float b = x3 - x0 + h * x3;
        float d = y1 - y0 + g * y1;
        float e = y3 - y0 + h * y3;
        return new float[] {a, b, x0, d, e, y0, g, h};
    }

    private static float[] project(float[] m, float u, float v) {
        float w = m[6] * u + m[7] * v + 1f;
        if (Math.abs(w) < 1e-6f) {
            w = 1e-6f;
        }
        return new float[] {(m[0] * u + m[1] * v + m[2]) / w, (m[3] * u + m[4] * v + m[5]) / w};
    }

    /** Draws the frame and the handles over the picture, in screen space. */
    void drawHandles(Canvas c, Matrix toScreen, float density) {
        if (!showFrame) {
            return;
        }
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setColor(0xFF1E9BF0);
        line.setStrokeWidth(2f * density);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(0xFF0B6FD6);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xFFFFFFFF);
        ring.setStrokeWidth(1.5f * density);
        float r = 8f * density;
        if (mode == MODE_MESH && mesh != null) {
            int w = divX + 1;
            int h = divY + 1;
            float[] p = mesh.clone();
            toScreen.mapPoints(p);
            for (int j = 0; j < h; j++) {
                for (int i = 0; i < w - 1; i++) {
                    int a = 2 * (j * w + i);
                    c.drawLine(p[a], p[a + 1], p[a + 2], p[a + 3], line);
                }
            }
            for (int j = 0; j < h - 1; j++) {
                for (int i = 0; i < w; i++) {
                    int a = 2 * (j * w + i);
                    int b = 2 * ((j + 1) * w + i);
                    c.drawLine(p[a], p[a + 1], p[b], p[b + 1], line);
                }
            }
            for (int k = 0; k < w * h; k++) {
                c.drawCircle(p[2 * k], p[2 * k + 1], r, fill);
                c.drawCircle(p[2 * k], p[2 * k + 1], r, ring);
            }
            return;
        }
        float[] q = currentQuad();
        float[] p = q.clone();
        toScreen.mapPoints(p);
        for (int i = 0; i < 4; i++) {
            int n = (i + 1) % 4;
            c.drawLine(p[2 * i], p[2 * i + 1], p[2 * n], p[2 * n + 1], line);
        }
        if (mode == MODE_SCALE) {
            float[] rot = rotateHandle();
            toScreen.mapPoints(rot);
            float mx = (p[0] + p[2]) / 2f;
            float my = (p[1] + p[3]) / 2f;
            Paint stem = new Paint(Paint.ANTI_ALIAS_FLAG);
            stem.setColor(0xFF888888);
            stem.setStrokeWidth(1.5f * density);
            c.drawLine(mx, my, rot[0], rot[1], stem);
            Paint dark = new Paint(Paint.ANTI_ALIAS_FLAG);
            dark.setColor(0xFF111111);
            c.drawCircle(rot[0], rot[1], 14f * density, dark);
            c.drawCircle(rot[0], rot[1], 14f * density, ring);
        } else {
            for (int i = 0; i < 4; i++) {
                int n = (i + 1) % 4;
                float mx = (p[2 * i] + p[2 * n]) / 2f;
                float my = (p[2 * i + 1] + p[2 * n + 1]) / 2f;
                c.drawCircle(mx, my, r, fill);
                c.drawCircle(mx, my, r, ring);
            }
        }
        for (int i = 0; i < 4; i++) {
            c.drawCircle(p[2 * i], p[2 * i + 1], r, fill);
            c.drawCircle(p[2 * i], p[2 * i + 1], r, ring);
        }
    }

    /** The rotate handle, a little above the middle of the top edge (canvas pixels). */
    private float[] rotateHandle() {
        float[] q = scaleQuad();
        float mx = (q[0] + q[2]) / 2f;
        float my = (q[1] + q[3]) / 2f;
        float cx = (q[0] + q[4]) / 2f;
        float cy = (q[1] + q[5]) / 2f;
        float dx = mx - cx;
        float dy = my - cy;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1e-3f) {
            return new float[] {mx, my - 40f};
        }
        float extra = Math.max(40f, len * 0.25f);
        return new float[] {mx + dx / len * extra, my + dy / len * extra};
    }

    // ------------------------------------------------------------------ touch

    /**
     * @param inverse screen to canvas matrix
     * @param density screen density, to size the touch targets
     * @param scale   screen pixels per canvas pixel
     */
    boolean onTouch(MotionEvent e, Matrix inverse, float density, float scale) {
        float reach = 26f * density / Math.max(scale, 1e-3f);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float[] pt = map(inverse, e.getX(), e.getY());
                lastX = pt[0];
                lastY = pt[1];
                twoFingers = false;
                pickTarget(pt[0], pt[1], reach);
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2 && mode == MODE_SCALE) {
                    twoFingers = true;
                    drag = DRAG_NONE;
                    float[] a = map(inverse, e.getX(0), e.getY(0));
                    float[] b = map(inverse, e.getX(1), e.getY(1));
                    baseDist = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
                    baseAngle = (float) Math.toDegrees(Math.atan2(b[1] - a[1], b[0] - a[0]));
                    baseZoom = zoom;
                    baseParamAngle = angle;
                    lastX = (a[0] + b[0]) / 2f;
                    lastY = (a[1] + b[1]) / 2f;
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (twoFingers && e.getPointerCount() >= 2) {
                    float[] a = map(inverse, e.getX(0), e.getY(0));
                    float[] b = map(inverse, e.getX(1), e.getY(1));
                    float dist = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
                    float ang = (float) Math.toDegrees(Math.atan2(b[1] - a[1], b[0] - a[0]));
                    if (baseDist > 1f) {
                        zoom = clampZoom(baseZoom * dist / baseDist);
                    }
                    angle = wrap(baseParamAngle + (ang - baseAngle));
                    float mx = (a[0] + b[0]) / 2f;
                    float my = (a[1] + b[1]) / 2f;
                    tx += mx - lastX;
                    ty += my - lastY;
                    lastX = mx;
                    lastY = my;
                    changed();
                    return true;
                }
                if (e.getPointerCount() != 1 || drag == DRAG_NONE) {
                    return true;
                }
                float[] pt = map(inverse, e.getX(), e.getY());
                dragTo(pt[0], pt[1]);
                lastX = pt[0];
                lastY = pt[1];
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
                if (twoFingers) {
                    twoFingers = false;
                    drag = DRAG_NONE;
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                drag = DRAG_NONE;
                twoFingers = false;
                return true;
            default:
                return true;
        }
    }

    private static float[] map(Matrix m, float x, float y) {
        float[] p = {x, y};
        m.mapPoints(p);
        return p;
    }

    private void pickTarget(float x, float y, float reach) {
        drag = DRAG_MOVE;
        if (mode == MODE_SCALE) {
            float[] rot = rotateHandle();
            if (Math.hypot(x - rot[0], y - rot[1]) < reach * 1.2f) {
                drag = DRAG_ROTATE;
                return;
            }
            float[] q = scaleQuad();
            for (int i = 0; i < 4; i++) {
                if (Math.hypot(x - q[2 * i], y - q[2 * i + 1]) < reach) {
                    drag = DRAG_CORNER;
                    dragIndex = i;
                    return;
                }
            }
        } else if (mode == MODE_PERSPECTIVE) {
            for (int i = 0; i < 4; i++) {
                if (Math.hypot(x - pc[2 * i], y - pc[2 * i + 1]) < reach) {
                    drag = DRAG_POINT;
                    dragIndex = i;
                    return;
                }
            }
            for (int i = 0; i < 4; i++) {
                int n = (i + 1) % 4;
                float mx = (pc[2 * i] + pc[2 * n]) / 2f;
                float my = (pc[2 * i + 1] + pc[2 * n + 1]) / 2f;
                if (Math.hypot(x - mx, y - my) < reach) {
                    drag = DRAG_EDGE;
                    dragIndex = i;
                    return;
                }
            }
        } else if (mesh != null) {
            int best = -1;
            double bestD = reach;
            for (int k = 0; k < mesh.length / 2; k++) {
                double d = Math.hypot(x - mesh[2 * k], y - mesh[2 * k + 1]);
                if (d < bestD) {
                    bestD = d;
                    best = k;
                }
            }
            if (best >= 0) {
                drag = DRAG_POINT;
                dragIndex = best;
            }
        }
    }

    private void dragTo(float x, float y) {
        float dx = x - lastX;
        float dy = y - lastY;
        switch (drag) {
            case DRAG_MOVE:
                if (mode == MODE_SCALE) {
                    tx += dx;
                    ty += dy;
                } else if (mode == MODE_PERSPECTIVE) {
                    for (int i = 0; i < 4; i++) {
                        pc[2 * i] += dx;
                        pc[2 * i + 1] += dy;
                    }
                } else if (mesh != null) {
                    for (int k = 0; k < mesh.length / 2; k++) {
                        mesh[2 * k] += dx;
                        mesh[2 * k + 1] += dy;
                    }
                }
                break;
            case DRAG_CORNER: {
                float ccx = cx0 + tx;
                float ccy = cy0 + ty;
                float half = (float) Math.hypot(sw / 2f, sh / 2f);
                zoom = clampZoom((float) Math.hypot(x - ccx, y - ccy) / half);
                break;
            }
            case DRAG_ROTATE: {
                float ccx = cx0 + tx;
                float ccy = cy0 + ty;
                angle = wrap((float) Math.toDegrees(Math.atan2(y - ccy, x - ccx)) + 90f);
                break;
            }
            case DRAG_POINT:
                if (mode == MODE_PERSPECTIVE) {
                    pc[2 * dragIndex] += dx;
                    pc[2 * dragIndex + 1] += dy;
                } else if (mesh != null) {
                    mesh[2 * dragIndex] += dx;
                    mesh[2 * dragIndex + 1] += dy;
                }
                break;
            case DRAG_EDGE: {
                int n = (dragIndex + 1) % 4;
                pc[2 * dragIndex] += dx;
                pc[2 * dragIndex + 1] += dy;
                pc[2 * n] += dx;
                pc[2 * n + 1] += dy;
                break;
            }
            default:
                return;
        }
        changed();
    }

    private static float clampZoom(float z) {
        return Math.max(0.01f, Math.min(10f, z));
    }

    private static float wrap(float a) {
        while (a > 180f) {
            a -= 360f;
        }
        while (a < -180f) {
            a += 360f;
        }
        return a;
    }

    // ------------------------------------------------------------------ values for the panel

    void setTranslation(float x, float y) {
        tx = x;
        ty = y;
        changed();
    }

    void setZoom(float z) {
        zoom = clampZoom(z);
        changed();
    }

    void setAngle(float a) {
        angle = wrap(a);
        changed();
    }

    void setPerspective(boolean on) {
        perspective = on;
        changed();
    }

    void setSmoothness(int s) {
        smoothness = Math.max(0, Math.min(100, s));
        changed();
    }

    void setInterpolation(boolean smooth) {
        smoothFilter = smooth;
        changed();
    }

    void setShowFrame(boolean on) {
        showFrame = on;
        changed();
    }
}
