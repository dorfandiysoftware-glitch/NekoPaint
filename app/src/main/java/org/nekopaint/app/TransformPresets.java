// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Saved presets of the Custom transform: a name plus the points, as fractions of the picture. */
final class TransformPresets {

    private TransformPresets() {
    }

    static List<String> names(Context c) {
        List<String> out = new ArrayList<String>();
        JSONArray all = read(c);
        for (int i = 0; i < all.length(); i++) {
            out.add(all.optJSONObject(i).optString("name"));
        }
        return out;
    }

    static JSONObject get(Context c, String name) {
        JSONArray all = read(c);
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && name.equals(o.optString("name"))) {
                return o;
            }
        }
        return null;
    }

    /** Adds the preset, replacing one with the same name. */
    static void save(Context c, String name, JSONObject preset) {
        try {
            preset.put("name", name);
            JSONArray all = read(c);
            JSONArray out = new JSONArray();
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.optJSONObject(i);
                if (o != null && !name.equals(o.optString("name"))) {
                    out.put(o);
                }
            }
            out.put(preset);
            Prefs.setTransformPresets(c, out.toString());
        } catch (JSONException ignored) {
            // the name and the numbers are always valid JSON
        }
    }

    static void delete(Context c, String name) {
        JSONArray all = read(c);
        JSONArray out = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && !name.equals(o.optString("name"))) {
                out.put(o);
            }
        }
        Prefs.setTransformPresets(c, out.toString());
    }

    private static JSONArray read(Context c) {
        try {
            return new JSONArray(Prefs.transformPresets(c));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }
}
