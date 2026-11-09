// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;

/** What the phone's graphics chip can do: which of OpenGL ES and Vulkan it offers. */
final class GpuInfo {

    String renderer = "?";
    String vendor = "?";
    /** OpenGL ES version the device reports, such as "3.2". */
    String glEs = "?";
    boolean glEs3;
    boolean vulkan;
    String vulkanVersion = "—";

    static GpuInfo detect(Context c) {
        GpuInfo g = new GpuInfo();
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            int v = am.getDeviceConfigurationInfo().reqGlEsVersion;
            g.glEs = (v >> 16) + "." + (v & 0xFFFF);
            g.glEs3 = (v >> 16) >= 3;
        } catch (RuntimeException e) {
            // keep the defaults
        }
        try {
            PackageManager pm = c.getPackageManager();
            for (FeatureInfo f : pm.getSystemAvailableFeatures()) {
                if (PackageManager.FEATURE_VULKAN_HARDWARE_VERSION.equals(f.name)) {
                    g.vulkan = true;
                    int ver = f.version;
                    g.vulkanVersion = (ver >> 22) + "." + ((ver >> 12) & 0x3FF);
                }
            }
        } catch (RuntimeException e) {
            // keep the defaults
        }
        readRenderer(g);
        return g;
    }

    /** Opens a tiny off-screen OpenGL context only to read the chip's name. */
    private static void readRenderer(GpuInfo g) {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        try {
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return;
            }
            int[] attrs = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] < 1) {
                return;
            }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                    new int[] {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            if (EGL14.eglMakeCurrent(display, surface, surface, context)) {
                String r = GLES20.glGetString(GLES20.GL_RENDERER);
                String v = GLES20.glGetString(GLES20.GL_VENDOR);
                if (r != null) {
                    g.renderer = r;
                }
                if (v != null) {
                    g.vendor = v;
                }
            }
        } catch (RuntimeException e) {
            // the name stays unknown
        } finally {
            try {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                if (surface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, surface);
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context);
                }
                EGL14.eglTerminate(display);
            } catch (RuntimeException e) {
                // nothing more to do
            }
        }
    }

    /** The API to use when the user has not chosen: OpenGL ES if present, else Vulkan, else CPU. */
    String defaultApi() {
        return glEs3 ? "opengl" : vulkan ? "vulkan" : "cpu";
    }
}
