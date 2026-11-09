// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.content.res.AppCompatResources;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * The "New" screen: new folder, import a picture, and canvas size presets for illustrations,
 * printing and animation, in the manner of ibis Paint.
 */
final class NewDrawingDialog {

    interface Listener {
        void onCreate(int width, int height);

        void onFolder();

        void onImport();
    }

    private static final int ILLUSTRATION = 0;
    private static final int PRINT = 1;
    private static final int ANIMATION = 2;

    private static final int[] DPI = {150, 300, 350, 400, 600};

    private static final class Variant {
        final String name;
        final int w;
        final int h;

        Variant(String name, int w, int h) {
            this.name = name;
            this.w = w;
            this.h = h;
        }

        boolean ok() {
            return Prefs.sizeOk(w, h);
        }
    }

    private final Context context;
    private final Listener listener;
    private final float density;
    private final int ink;
    private Dialog dialog;

    private NewDrawingDialog(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.density = context.getResources().getDisplayMetrics().density;
        this.ink = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorOnSurface, 0xFF000000);
    }

    static void show(Context context, Listener listener) {
        new NewDrawingDialog(context, listener).open();
    }

    private int dp(int v) {
        return (int) (v * density);
    }

    // ------------------------------------------------------------------ build

    private void open() {
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);

        addPlainRow(list, R.drawable.ic_folder, context.getString(R.string.new_folder), () -> {
            dialog.dismiss();
            listener.onFolder();
        });
        addPlainRow(list, R.drawable.ic_camera, context.getString(R.string.import_picture), () -> {
            dialog.dismiss();
            listener.onImport();
        });

        addSection(list, R.string.cat_illustration);
        addCustomIllustration(list);
        addVariantRow(list, ILLUSTRATION, "hd", "HD (Quality)", false, 1,
                new Variant[] {
                    new Variant("SD", 540, 1230), new Variant("HD", 1080, 2460),
                    new Variant("FHD", 1394, 3175), new Variant("UHD", 1799, 4096)});
        addVariantRow(list, ILLUSTRATION, "sq", "1 : 1", true, 2,
                new Variant[] {
                    new Variant("LD", 768, 768), new Variant("SD", 1280, 1280),
                    new Variant("2K", 2048, 2048), new Variant("3K", 3072, 3072),
                    new Variant("4K", 4096, 4096)});
        addVariantRow(list, ILLUSTRATION, "34", "3 : 4", true, 1,
                new Variant[] {
                    new Variant("SD", 768, 1024), new Variant("2K", 1536, 2048),
                    new Variant("3K", 2304, 3072), new Variant("4K", 3072, 4096)});
        addVariantRow(list, ILLUSTRATION, "916", "9 : 16", true, 1,
                new Variant[] {
                    new Variant("SD", 720, 1280), new Variant("2K", 1080, 1920),
                    new Variant("3K", 1440, 2560), new Variant("4K", 2160, 3840)});
        addFixedRow(list, ILLUSTRATION, "X Header", 1500, 500);
        addFixedRow(list, ILLUSTRATION, "Chat Stamp", 370, 320);
        addFixedRow(list, ILLUSTRATION, context.getString(R.string.preset_vertical), 690, 4096);

        addSection(list, R.string.cat_print);
        // Manga manuscript: B5 at 300 dpi for now, without frame guides.
        addFixedRow(list, PRINT, "Manga Manuscript", 2079, 2953, false);
        addPrintRow(list, "postcard", "Postcard (JP)", 1,
                new int[][] {{591, 874}, {1181, 1748}, {1378, 2039}, {1575, 2331}, {2362, 3496}});
        addPrintRow(list, "a4", "A4", 0,
                new int[][] {{1240, 1754}, {2480, 3508}, {2894, 4093}, {3307, 4677}, {4961, 7016}});
        addPrintRow(list, "a5", "A5", 0,
                new int[][] {{874, 1240}, {1748, 2480}, {2039, 2894}, {2331, 3307}, {3496, 4961}});
        addPrintRow(list, "b4", "B4", 0,
                new int[][] {{1476, 2085}, {2953, 4169}, {3445, 4864}, {3937, 5559}, {5906, 8339}});
        addPrintRow(list, "b5", "B5", 0,
                new int[][] {{1039, 1476}, {2079, 2953}, {2425, 3445}, {2772, 3937}, {4157, 5906}});
        addCustomPrint(list);

        addSection(list, R.string.cat_animation);
        addVariantRow(list, ANIMATION, "a11", "1 : 1", true, 1,
                new Variant[] {
                    new Variant("LLLD", 192, 192), new Variant("LLD", 640, 640),
                    new Variant("LD", 768, 768), new Variant("SD", 1280, 1280)});
        addVariantRow(list, ANIMATION, "a34", "3 : 4", true, 1,
                new Variant[] {
                    new Variant("LLD", 256, 192), new Variant("LD", 640, 480),
                    new Variant("SD", 1024, 768), new Variant("HD", 1280, 960)});
        addVariantRow(list, ANIMATION, "a916", "9 : 16", true, 0,
                new Variant[] {
                    new Variant("SD", 1280, 720), new Variant("2K", 1920, 1080)});
        addCustomAnimation(list);

        addSection(list, R.string.cat_brush);
        addPlainRow(list, R.drawable.ic_brush_pattern,
                context.getString(R.string.brush_pattern), () ->
                        Toast.makeText(context, R.string.coming_soon, Toast.LENGTH_SHORT).show());

        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);

        dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.new_title)
                .setView(scroll)
                .setNegativeButton(R.string.close, null)
                .create();
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            int h = context.getResources().getDisplayMetrics().heightPixels;
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (int) (h * 0.94f));
        }
    }

    // ------------------------------------------------------------------ rows

    private LinearLayout newRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(60));
        row.setPadding(dp(8), dp(4), dp(8), dp(4));
        android.util.TypedValue tv = new android.util.TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            row.setBackgroundResource(tv.resourceId);
        }
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private TextView label(LinearLayout row, String text) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(15);
        t.setTextColor(ink);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setMinWidth(dp(56));
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return t;
    }

    private void addSection(LinearLayout parent, int title) {
        TextView t = new TextView(context);
        t.setText(title);
        t.setTextSize(14);
        t.setPadding(dp(16), dp(10), dp(16), dp(10));
        t.setTextColor(MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorOnSurfaceVariant, ink));
        t.setBackgroundColor(MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorSurfaceContainerHighest, 0x22888888));
        parent.addView(t);
    }

    private void addPlainRow(LinearLayout parent, int icon, String text, Runnable action) {
        LinearLayout row = newRow(parent);
        ImageView iv = new ImageView(context);
        iv.setImageDrawable(AppCompatResources.getDrawable(context, icon));
        row.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView t = label(row, text);
        t.setPadding(dp(12), 0, 0, 0);
        row.setOnClickListener(v -> action.run());
    }

    /** A drawing-shaped icon: dashed for illustrations, solid for print, with play for animation. */
    private Drawable shapeIcon(int kind, int w, int h) {
        float box = 34f;
        float ratio = (float) w / h;
        float iw = ratio >= 1 ? box : box * ratio;
        float ih = ratio >= 1 ? box / ratio : box;
        iw = Math.max(iw, 12f);
        ih = Math.max(ih, 12f);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0);
        if (kind == ILLUSTRATION) {
            g.setStroke(dp(2), ink, dp(5), dp(3));
        } else {
            g.setStroke(dp(2), ink);
        }
        g.setSize(dp(Math.round(iw)), dp(Math.round(ih)));
        if (kind != ANIMATION) {
            return g;
        }
        Drawable play = AppCompatResources.getDrawable(context, R.drawable.ic_play);
        LayerDrawable ld = new LayerDrawable(new Drawable[] {g, play});
        ld.setLayerGravity(1, Gravity.CENTER);
        ld.setLayerSize(1, dp(18), dp(18));
        return ld;
    }

    private ImageView iconView(LinearLayout row, int kind, int w, int h) {
        ImageView iv = new ImageView(context);
        iv.setImageDrawable(shapeIcon(kind, w, h));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        row.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));
        return iv;
    }

    private MaterialButton valueButton(LinearLayout row, int iconRes) {
        MaterialButton b = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setMaxLines(1);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setIconPadding(dp(4));
        b.setIconResource(iconRes);
        b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_END);
        b.setIconTint(android.content.res.ColorStateList.valueOf(ink));
        b.setTextColor(ink);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        lp.setMarginStart(dp(8));
        row.addView(b, lp);
        return b;
    }

    private void create(int w, int h) {
        if (!Prefs.sizeOk(w, h)) {
            Toast.makeText(context, R.string.bad_size, Toast.LENGTH_LONG).show();
            return;
        }
        dialog.dismiss();
        listener.onCreate(w, h);
    }

    private static String size(int w, int h) {
        return w + " × " + h;
    }

    private void addFixedRow(LinearLayout parent, int kind, String name, int w, int h) {
        addFixedRow(parent, kind, name, w, h, true);
    }

    private void addFixedRow(LinearLayout parent, int kind, String name, int w, int h,
            boolean showSize) {
        LinearLayout row = newRow(parent);
        iconView(row, kind, w, h);
        TextView t = label(row, name);
        t.setPadding(dp(12), 0, 0, 0);
        if (showSize) {
            TextView v = new TextView(context);
            v.setText(size(w, h));
            v.setTextSize(14);
            v.setTextColor(ink);
            row.addView(v);
        }
        row.setOnClickListener(x -> create(w, h));
    }

    /** A row with a drop-down of sizes, such as "3 : 4 (2K)  1536 × 2048". */
    private void addVariantRow(LinearLayout parent, int kind, String key, String base,
            boolean nameInLabel, int def, Variant[] variants) {
        final String pref = "np_" + key;
        final int[] sel = {Math.min(Math.max(Prefs.getInt(context, pref, def), 0),
                variants.length - 1)};
        if (!variants[sel[0]].ok()) {
            sel[0] = def;
        }
        LinearLayout row = newRow(parent);
        final ImageView icon = iconView(row, kind, variants[sel[0]].w, variants[sel[0]].h);
        final TextView name = label(row, base);
        name.setPadding(dp(12), 0, 0, 0);
        final MaterialButton button = valueButton(row, R.drawable.ic_up);
        Runnable refresh = () -> {
            Variant v = variants[sel[0]];
            name.setText(nameInLabel ? base + " (" + v.name + ")" : base);
            button.setText(size(v.w, v.h));
            icon.setImageDrawable(shapeIcon(kind, v.w, v.h));
        };
        refresh.run();
        button.setOnClickListener(x -> {
            String[] left = new String[variants.length];
            String[] right = new String[variants.length];
            boolean[] ok = new boolean[variants.length];
            for (int i = 0; i < variants.length; i++) {
                left[i] = nameInLabel ? base + " (" + variants[i].name + ")" : variants[i].name;
                right[i] = size(variants[i].w, variants[i].h);
                ok[i] = variants[i].ok();
            }
            popup(button, left, right, ok, sel[0], i -> {
                sel[0] = i;
                Prefs.putInt(context, pref, i);
                refresh.run();
            });
        });
        row.setOnClickListener(x -> create(variants[sel[0]].w, variants[sel[0]].h));
    }

    private void addPrintRow(LinearLayout parent, String key, String base, int def, int[][] sizes) {
        Variant[] v = new Variant[sizes.length];
        for (int i = 0; i < v.length; i++) {
            v[i] = new Variant(DPI[i] + " dpi", sizes[i][0], sizes[i][1]);
        }
        final String pref = "np_" + key;
        final int[] sel = {Math.min(Math.max(Prefs.getInt(context, pref, def), 0), v.length - 1)};
        if (!v[sel[0]].ok()) {
            sel[0] = def;
        }
        LinearLayout row = newRow(parent);
        iconView(row, PRINT, 3, 4);
        TextView name = label(row, base);
        name.setPadding(dp(12), 0, 0, 0);
        final MaterialButton button = valueButton(row, R.drawable.ic_up);
        Runnable refresh = () -> button.setText(
                v[sel[0]].name + "  " + size(v[sel[0]].w, v[sel[0]].h));
        refresh.run();
        button.setOnClickListener(x -> {
            String[] left = new String[v.length];
            String[] right = new String[v.length];
            boolean[] ok = new boolean[v.length];
            for (int i = 0; i < v.length; i++) {
                left[i] = base + " " + v[i].name;
                right[i] = size(v[i].w, v[i].h);
                ok[i] = v[i].ok();
            }
            popup(button, left, right, ok, sel[0], i -> {
                sel[0] = i;
                Prefs.putInt(context, pref, i);
                refresh.run();
            });
        });
        row.setOnClickListener(x -> create(v[sel[0]].w, v[sel[0]].h));
    }

    private interface Pick {
        void on(int index);
    }

    private void popup(View anchor, String[] left, String[] right, boolean[] ok, int selected,
            Pick pick) {
        final ListPopupWindow pop = new ListPopupWindow(context);
        pop.setAnchorView(anchor);
        pop.setModal(true);
        pop.setWidth(dp(300));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorSurfaceContainer, 0xFF303030));
        bg.setCornerRadius(dp(20));
        pop.setBackgroundDrawable(bg);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, 0, left) {
            @Override
            public View getView(int position, View convert, ViewGroup parent) {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(14), dp(16), dp(14));
                if (position == selected) {
                    row.setBackgroundColor(MaterialColors.getColor(context,
                            com.google.android.material.R.attr.colorSecondaryContainer, 0));
                }
                TextView a = new TextView(context);
                a.setText(left[position]);
                a.setTextColor(ink);
                a.setTextSize(15);
                row.addView(a, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView b = new TextView(context);
                b.setText(right[position]);
                b.setTextColor(ink);
                b.setTextSize(16);
                row.addView(b);
                row.setAlpha(ok[position] ? 1f : 0.4f);
                return row;
            }
        };
        pop.setAdapter(adapter);
        pop.setOnItemClickListener((p, v, position, id) -> {
            if (!ok[position]) {
                Toast.makeText(context, R.string.too_big, Toast.LENGTH_SHORT).show();
                return;
            }
            pick.on(position);
            pop.dismiss();
        });
        pop.show();
    }

    // ------------------------------------------------------------------ custom sizes

    private void addCustomIllustration(LinearLayout parent) {
        LinearLayout row = newRow(parent);
        iconView(row, ILLUSTRATION, 1, 1);
        TextView name = label(row, context.getString(R.string.preset_custom));
        name.setPadding(dp(12), 0, 0, 0);
        final MaterialButton b = valueButton(row, R.drawable.ic_edit);
        Runnable refresh = () -> b.setText(size(Prefs.getInt(context, "cust_w", 2000),
                Prefs.getInt(context, "cust_h", 2000)));
        refresh.run();
        b.setOnClickListener(x -> sizeDialog("cust_w", "cust_h", 2000, 2000, false, refresh));
        row.setOnClickListener(x -> create(Prefs.getInt(context, "cust_w", 2000),
                Prefs.getInt(context, "cust_h", 2000)));
    }

    private void addCustomAnimation(LinearLayout parent) {
        LinearLayout row = newRow(parent);
        iconView(row, ANIMATION, 1, 1);
        TextView name = label(row, context.getString(R.string.preset_custom));
        name.setPadding(dp(12), 0, 0, 0);
        final MaterialButton b = valueButton(row, R.drawable.ic_edit);
        Runnable refresh = () -> b.setText(size(Prefs.getInt(context, "anim_w", 1000),
                Prefs.getInt(context, "anim_h", 1000)));
        refresh.run();
        b.setOnClickListener(x -> sizeDialog("anim_w", "anim_h", 1000, 1000, true, refresh));
        row.setOnClickListener(x -> create(Prefs.getInt(context, "anim_w", 1000),
                Prefs.getInt(context, "anim_h", 1000)));
    }

    private LinearLayout sliderRow(String letter, int value, EditText field, SeekBar bar) {
        LinearLayout r = new LinearLayout(context);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(context);
        l.setText(letter);
        l.setTextSize(18);
        l.setTextColor(ink);
        r.addView(l, new LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT));
        r.addView(stepButton("−", bar, -1));
        bar.setMax(Prefs.MAX_SIDE - 1);
        bar.setProgress(value - 1);
        r.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(stepButton("+", bar, 1));
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setText(String.valueOf(value));
        field.setSelectAllOnFocus(true);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setGravity(Gravity.END);
        r.addView(field, new LinearLayout.LayoutParams(dp(72),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private View stepButton(String text, SeekBar bar, int delta) {
        MaterialButton b = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialIconButtonFilledTonalStyle);
        b.setText(text);
        b.setTextSize(20);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(0, 0, 0, 0);
        b.setOnClickListener(v -> bar.setProgress(
                Math.max(0, Math.min(bar.getMax(), bar.getProgress() + delta))));
        return wrap(b, 40);
    }

    private View wrap(View v, int size) {
        LinearLayout h = new LinearLayout(context);
        h.addView(v, new LinearLayout.LayoutParams(dp(size), dp(size)));
        return h;
    }

    private static int parse(EditText e, int fallback) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /** Width and height with sliders; for animation also frames per second. */
    private void sizeDialog(String keyW, String keyH, int defW, int defH, boolean animation,
            Runnable done) {
        int w0 = Prefs.getInt(context, keyW, defW);
        int h0 = Prefs.getInt(context, keyH, defH);

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        final TextView summary = new TextView(context);
        summary.setTextSize(18);
        summary.setTextColor(ink);
        summary.setGravity(Gravity.END);
        box.addView(summary);

        final EditText wf = new EditText(context);
        final EditText hf = new EditText(context);
        final SeekBar wb = new SeekBar(context);
        final SeekBar hb = new SeekBar(context);
        box.addView(sliderRow("W", w0, wf, wb));
        box.addView(sliderRow("H", h0, hf, hb));
        final boolean[] busy = {false};
        Runnable upd = () -> summary.setText(size(parse(wf, w0), parse(hf, h0)));
        upd.run();
        bind(wb, wf, busy, upd);
        bind(hb, hf, busy, upd);

        final int[] fps = {Prefs.getInt(context, "anim_fps", 12)};
        if (animation) {
            TextView fl = new TextView(context);
            fl.setTextColor(ink);
            fl.setPadding(0, dp(16), 0, 0);
            box.addView(fl);
            SeekBar fb = new SeekBar(context);
            fb.setMax(59);
            fb.setProgress(fps[0] - 1);
            Runnable fu = () -> fl.setText(context.getString(R.string.fps, fps[0]));
            fu.run();
            fb.setOnSeekBarChangeListener(new SimpleSeek() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean user) {
                    fps[0] = p + 1;
                    fu.run();
                }
            });
            box.addView(fb);
        }

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.custom_size)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    int w = parse(wf, -1);
                    int h = parse(hf, -1);
                    if (!Prefs.sizeOk(w, h)) {
                        Toast.makeText(context, R.string.bad_size, Toast.LENGTH_LONG).show();
                        return;
                    }
                    Prefs.putInt(context, keyW, w);
                    Prefs.putInt(context, keyH, h);
                    if (animation) {
                        Prefs.putInt(context, "anim_fps", fps[0]);
                    }
                    done.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onProgressChanged(SeekBar s, int p, boolean user) {
        }

        @Override
        public void onStartTrackingTouch(SeekBar s) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar s) {
        }
    }

    /** Keeps a slider and its number field in step. */
    private void bind(final SeekBar bar, final EditText field, final boolean[] busy,
            final Runnable changed) {
        bar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (busy[0]) {
                    return;
                }
                busy[0] = true;
                field.setText(String.valueOf(p + 1));
                busy[0] = false;
                changed.run();
            }
        });
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (busy[0]) {
                    return;
                }
                int v = parse(field, -1);
                if (v >= 1 && v <= Prefs.MAX_SIDE) {
                    busy[0] = true;
                    bar.setProgress(v - 1);
                    busy[0] = false;
                }
                changed.run();
            }
        });
    }

    // ------------------------------------------------------------------ custom print size

    private void addCustomPrint(LinearLayout parent) {
        LinearLayout row = newRow(parent);
        iconView(row, PRINT, 1, 1);
        TextView name = label(row, context.getString(R.string.preset_custom));
        name.setPadding(dp(12), 0, 0, 0);
        final MaterialButton b = valueButton(row, R.drawable.ic_edit);
        Runnable refresh = () -> {
            int[] px = printPixels();
            b.setText(Prefs.getInt(context, "pr_dpi", 350) + " dpi " + size(px[0], px[1]));
        };
        refresh.run();
        b.setOnClickListener(x -> printDialog(refresh));
        row.setOnClickListener(x -> {
            int[] px = printPixels();
            create(px[0], px[1]);
        });
    }

    private int[] printPixels() {
        boolean inch = Prefs.getInt(context, "pr_inch", 0) == 1;
        double w = parseD(Prefs.getString(context, "pr_w", "100"), 100);
        double h = parseD(Prefs.getString(context, "pr_h", "100"), 100);
        int dpi = Prefs.getInt(context, "pr_dpi", 350);
        return pixels(w, h, inch, dpi);
    }

    private static int[] pixels(double w, double h, boolean inch, int dpi) {
        double wi = inch ? w : w / 25.4;
        double hi = inch ? h : h / 25.4;
        return new int[] {(int) Math.round(wi * dpi), (int) Math.round(hi * dpi)};
    }

    private static double parseD(String s, double fallback) {
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void printDialog(Runnable done) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        final TextView summary = new TextView(context);
        summary.setTextSize(18);
        summary.setTextColor(ink);
        summary.setGravity(Gravity.END);
        box.addView(summary);

        LinearLayout r1 = new LinearLayout(context);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        final EditText wf = decimalField(Prefs.getString(context, "pr_w", "100"));
        final EditText hf = decimalField(Prefs.getString(context, "pr_h", "100"));
        r1.addView(wf, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView times = new TextView(context);
        times.setText(" × ");
        times.setTextColor(ink);
        r1.addView(times);
        r1.addView(hf, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final MaterialButtonToggleGroup unit = new MaterialButtonToggleGroup(context);
        unit.setSingleSelection(true);
        unit.setSelectionRequired(true);
        MaterialButton mm = unitButton("mm");
        MaterialButton inch = unitButton("inch");
        unit.addView(mm);
        unit.addView(inch);
        unit.check(Prefs.getInt(context, "pr_inch", 0) == 1 ? inch.getId() : mm.getId());
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.setMarginStart(dp(8));
        r1.addView(unit, ulp);
        box.addView(r1);

        LinearLayout r2 = new LinearLayout(context);
        r2.setGravity(Gravity.CENTER_VERTICAL);
        final EditText df = new EditText(context);
        df.setInputType(InputType.TYPE_CLASS_NUMBER);
        df.setText(String.valueOf(Prefs.getInt(context, "pr_dpi", 350)));
        df.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        r2.addView(df, new LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView dl = new TextView(context);
        dl.setText("  dpi");
        dl.setTextColor(ink);
        r2.addView(dl);
        box.addView(r2);

        final Runnable upd = () -> {
            int[] px = pixels(parseD(wf.getText().toString(), 0), parseD(hf.getText().toString(), 0),
                    unit.getCheckedButtonId() == inch.getId(), parse(df, 0));
            summary.setText(size(px[0], px[1]));
        };
        TextWatcher tw = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                upd.run();
            }
        };
        wf.addTextChangedListener(tw);
        hf.addTextChangedListener(tw);
        df.addTextChangedListener(tw);
        unit.addOnButtonCheckedListener((g, id, checked) -> upd.run());
        upd.run();

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.custom_size)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    boolean in = unit.getCheckedButtonId() == inch.getId();
                    int dpi = parse(df, -1);
                    int[] px = pixels(parseD(wf.getText().toString(), 0),
                            parseD(hf.getText().toString(), 0), in, dpi);
                    if (dpi < 1 || !Prefs.sizeOk(px[0], px[1])) {
                        Toast.makeText(context, R.string.bad_size, Toast.LENGTH_LONG).show();
                        return;
                    }
                    Prefs.putString(context, "pr_w", wf.getText().toString().trim());
                    Prefs.putString(context, "pr_h", hf.getText().toString().trim());
                    Prefs.putInt(context, "pr_inch", in ? 1 : 0);
                    Prefs.putInt(context, "pr_dpi", dpi);
                    done.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private MaterialButton unitButton(String text) {
        MaterialButton b = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setId(View.generateViewId());
        b.setText(text);
        b.setAllCaps(false);
        b.setCheckable(true);
        return b;
    }

    private EditText decimalField(String value) {
        EditText e = new EditText(context);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(value);
        e.setSelectAllOnFocus(true);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        return e;
    }
}
