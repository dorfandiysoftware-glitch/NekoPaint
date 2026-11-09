// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.app.Activity;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

/** Fills the "graphics" block (view_gpu.xml) and remembers the choice. */
final class GpuPicker {

    private GpuPicker() {
    }

    static void bind(final Activity a, View root) {
        final TextView info = root.findViewById(R.id.gpu_info);
        final RadioGroup group = root.findViewById(R.id.gpu_group);
        final RadioButton gl = root.findViewById(R.id.gpu_gl);
        final RadioButton vk = root.findViewById(R.id.gpu_vk);
        final RadioButton cpu = root.findViewById(R.id.gpu_cpu);
        info.setText(R.string.gpu_checking);
        new Thread(() -> {
            final GpuInfo g = GpuInfo.detect(a);
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) {
                    return;
                }
                info.setText(a.getString(R.string.gpu_detected, g.renderer, g.vendor,
                        g.glEs, g.vulkan ? g.vulkanVersion : a.getString(R.string.gpu_none)));
                String def = g.defaultApi();
                gl.setEnabled(g.glEs3);
                vk.setEnabled(g.vulkan);
                gl.setText(label(a, R.string.gpu_gl, def.equals("opengl"), g.glEs3));
                vk.setText(label(a, R.string.gpu_vk, def.equals("vulkan"), g.vulkan));
                cpu.setText(label(a, R.string.gpu_cpu, def.equals("cpu"), true));
                String saved = Prefs.gpuApi(a);
                if (saved == null || !usable(saved, g)) {
                    saved = def;
                    Prefs.setGpuApi(a, saved);
                }
                group.check(saved.equals("opengl") ? R.id.gpu_gl
                        : saved.equals("vulkan") ? R.id.gpu_vk : R.id.gpu_cpu);
                group.setOnCheckedChangeListener((gr, id) -> Prefs.setGpuApi(a,
                        id == R.id.gpu_gl ? "opengl" : id == R.id.gpu_vk ? "vulkan" : "cpu"));
            });
        }, "gpu-detect").start();
    }

    private static boolean usable(String api, GpuInfo g) {
        return api.equals("cpu") || (api.equals("opengl") && g.glEs3)
                || (api.equals("vulkan") && g.vulkan);
    }

    private static String label(Activity a, int name, boolean isDefault, boolean supported) {
        String s = a.getString(name);
        if (!supported) {
            s += " — " + a.getString(R.string.gpu_unsupported);
        } else if (isDefault) {
            s += " (" + a.getString(R.string.gpu_default) + ")";
        }
        return s;
    }
}
