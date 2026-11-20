// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

/** The editor options from the settings page, with their default values. */
final class Opts {

    static final String UNDO_TAP = "g_undo2";
    static final String REDO_TAP = "g_redo3";
    static final String QUICK_PICK = "g_quick_pick";
    static final String QUICK_MS = "g_quick_ms";
    static final String SMOOTH_ZOOM = "u_smooth";
    static final String UNDO_MSG = "u_undo_msg";
    static final String ADD_CURRENT = "u_add_current";
    static final String TOOL_COLS = "u_tool_cols";
    static final String TOOL_LABELS = "u_tool_labels";
    static final String ROW_HEIGHT = "u_row_h";
    static final String BACKDROP = "u_backdrop";
    static final String PRESSURE = "p_on";
    static final String PRESSURE_CURVE = "p_curve";
    static final String OFFSET_X = "t_off_x";
    static final String OFFSET_Y = "t_off_y";
    static final String PALM = "s_palm";

    private static final String[] KEYS = {UNDO_TAP, REDO_TAP, QUICK_PICK, QUICK_MS, SMOOTH_ZOOM,
        UNDO_MSG, ADD_CURRENT, TOOL_COLS, TOOL_LABELS, ROW_HEIGHT, BACKDROP, PRESSURE,
        PRESSURE_CURVE, OFFSET_X, OFFSET_Y, PALM};

    private Opts() {
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("nekopaint", Context.MODE_PRIVATE);
    }

    static boolean flag(Context c, String key) {
        boolean def = true;
        return p(c).getBoolean(key, def);
    }

    static int num(Context c, String key) {
        return p(c).getInt(key, def(key));
    }

    private static int def(String key) {
        switch (key) {
            case QUICK_MS: return 500;
            case TOOL_COLS: return 2;
            case ROW_HEIGHT: return 56;
            case PRESSURE_CURVE: return 1;
            default: return 0; // BACKDROP 0 = the usual colour, offsets 0, PALM 0 = off
        }
    }

    static void setFlag(Context c, String key, boolean v) {
        p(c).edit().putBoolean(key, v).apply();
    }

    static void setNum(Context c, String key, int v) {
        p(c).edit().putInt(key, v).apply();
    }

    /** Pressure exponent: below 1 makes soft pressure count for more. */
    static float pressureGamma(Context c) {
        int curve = num(c, PRESSURE_CURVE);
        return curve == 0 ? 1.7f : curve == 2 ? 0.6f : 1f;
    }

    static void reset(Context c) {
        SharedPreferences.Editor e = p(c).edit();
        for (String k : KEYS) {
            e.remove(k);
        }
        e.apply();
    }

    /** All editor options as JSON text, for the backup file. */
    static String export(Context c) throws JSONException {
        JSONObject o = new JSONObject();
        Map<String, ?> all = p(c).getAll();
        for (String k : KEYS) {
            Object v = all.get(k);
            if (v != null) {
                o.put(k, v);
            }
        }
        return o.toString(2);
    }

    /** Reads a backup made by {@link #export}; unknown keys are ignored. */
    static void importJson(Context c, String text) throws JSONException {
        JSONObject o = new JSONObject(text);
        SharedPreferences.Editor e = p(c).edit();
        for (String k : KEYS) {
            if (!o.has(k)) {
                continue;
            }
            Object v = o.get(k);
            if (v instanceof Boolean) {
                e.putBoolean(k, (Boolean) v);
            } else if (v instanceof Number) {
                e.putInt(k, ((Number) v).intValue());
            }
        }
        e.apply();
    }
}
