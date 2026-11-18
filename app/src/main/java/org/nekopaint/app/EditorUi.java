// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.slider.Slider;

import nekopaint.core.BlendMode;
import nekopaint.core.Document;
import nekopaint.core.Layer;

/**
 * The bottom bar of the editor and the panels that open above it: tools, brush, colour, layers
 * and the small menu. Everything is built in code so the classic grey look and the Material 3
 * look share one implementation.
 */
final class EditorUi {

    /** What the activity has to provide. */
    interface Host {
        void onLayersEdited();

        void showMenu(View anchor);
    }

    private static final int TOOL_BRUSH = 0;
    private static final int TOOL_ERASER = 1;
    private static final int TOOL_PICKER = 2;

    private final MainActivity act;
    private final CanvasView canvas;
    private final Host host;
    private final boolean classic;
    private final View topBar;
    private final int barBg;
    private final int panelBg;
    private final int fg;
    private final int fgDim;
    private final int accent;
    private final int rowSelected;
    private final float density;

    private final LinearLayout bar;
    private LinearLayout buttonRow;
    private LinearLayout slidersBox;
    private Slider sizeSlider;
    private Slider flowSlider;
    private TextView sizeText;
    private TextView flowText;
    private BrushPreview circle;
    private TextView circleText;
    private View colorBoxView;
    private float hardness = 0.8f;
    private float spacing = 0.15f;
    private int brushIndex = 2;
    private ImageButton swapButton;
    private ImageButton toolButton;
    private View brushButton;
    private View colorSwatch;
    private ImageButton liteButton;
    private ImageButton layersButton;
    private TextView layersCount;
    private ImageButton moreButton;
    private View[] fullBarItems;

    private int tool = TOOL_BRUSH;
    private int color = 0x000000;
    private float sizePos = 8f;
    private float flowPos = 100f;
    private boolean lite;
    private PopupWindow open;
    private LinearLayout layerRows;
    private Slider layerOpacity;
    private TextView layerOpacityText;
    private TextView blendButton;
    private boolean syncing;

    EditorUi(MainActivity act, CanvasView canvas, LinearLayout barHost, View topBar,
            boolean classic, Host host) {
        this.act = act;
        this.canvas = canvas;
        this.host = host;
        this.classic = classic;
        this.topBar = topBar;
        this.density = act.getResources().getDisplayMetrics().density;
        if (classic) {
            barBg = 0xFF6B6B6B;
            panelBg = 0xF2505050;
            fg = Color.WHITE;
            fgDim = 0xB3FFFFFF;
            accent = 0xFF3F6FB5;
            rowSelected = 0x663F6FB5;
        } else {
            barBg = act.themeColor(com.google.android.material.R.attr.colorSurfaceContainer);
            panelBg = act.themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh);
            fg = act.themeColor(com.google.android.material.R.attr.colorOnSurface);
            fgDim = act.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant);
            accent = act.themeColor(androidx.appcompat.R.attr.colorPrimary);
            rowSelected = act.themeColor(com.google.android.material.R.attr.colorSecondaryContainer);
        }
        bar = barHost;
        bar.setBackgroundColor(classic ? 0xFFC0C0C0 : act.themeColor(com.google.android.material.R.attr.colorSurface));
        buildBar();
        canvas.setColor(color);
        refreshSwatch();
        applyBrush();
    }

    // ------------------------------------------------------------------ small helpers

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** Slider position 1..150 to brush radius: linear up to 50, then growing to 1000. */
    static float radiusFor(float position) {
        if (position <= 50f) {
            return position;
        }
        return (float) (50.0 * Math.pow(20.0, (position - 50.0) / 100.0));
    }

    private ImageButton iconButton(int drawable, int descRes) {
        ImageButton b = new ImageButton(act);
        b.setImageResource(drawable);
        b.setImageTintList(ColorStateList.valueOf(fg));
        b.setBackground(null);
        b.setContentDescription(act.getString(descRes));
        b.setScaleType(ImageView.ScaleType.CENTER);
        return b;
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView label(String text, float sp, int color) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    // ------------------------------------------------------------------ the bar

    private void buildBar() {
        bar.setOrientation(LinearLayout.VERTICAL);

        // The two sliders stay on screen above the buttons, like in a painting app.
        slidersBox = new LinearLayout(act);
        slidersBox.setOrientation(LinearLayout.VERTICAL);
        slidersBox.setPadding(dp(4), dp(2), dp(8), dp(2));
        sizeText = label("", 14, classic ? Color.BLACK : fg);
        flowText = label("", 14, classic ? Color.BLACK : fg);
        sizeSlider = slider(1, 150, sizePos);
        flowSlider = slider(1, 100, flowPos);
        slidersBox.addView(sliderRow(sizeText, sizeSlider));
        slidersBox.addView(sliderRow(flowText, flowSlider));
        sizeSlider.addOnChangeListener((sl, v, user) -> applyBrush());
        flowSlider.addOnChangeListener((sl, v, user) -> applyBrush());
        bar.addView(slidersBox);

        buttonRow = new LinearLayout(act);
        buttonRow.setGravity(Gravity.CENTER_VERTICAL);
        buttonRow.setBackgroundColor(barBg);
        bar.addView(buttonRow);

        swapButton = iconButton(R.drawable.ic_swap_tools, R.string.eraser);
        toolButton = iconButton(R.drawable.ic_brush, R.string.tools);
        liteButton = iconButton(R.drawable.ic_expand_more, R.string.lite_mode);
        moreButton = iconButton(R.drawable.ic_chevron_left, R.string.menu);
        layersButton = iconButton(R.drawable.ic_layers, R.string.layers);
        // The tap goes to the box around it; a clickable button inside would swallow it.
        layersButton.setClickable(false);
        layersButton.setFocusable(false);

        FrameLayout brushBox = new FrameLayout(act);
        circle = new BrushPreview(act, null);
        brushBox.addView(circle, new FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER));
        circleText = label("", 11, Color.WHITE);
        circleText.setTypeface(Typeface.DEFAULT_BOLD);
        circleText.setShadowLayer(3f, 0f, 0f, Color.BLACK);
        brushBox.addView(circleText, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        brushBox.setContentDescription(act.getString(R.string.brush_settings));
        brushButton = brushBox;

        FrameLayout colorBox = new FrameLayout(act);
        colorSwatch = new View(act);
        colorBox.addView(colorSwatch, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
        colorBox.setContentDescription(act.getString(R.string.color));
        colorBoxView = colorBox;

        FrameLayout layersBox = new FrameLayout(act);
        layersBox.addView(layersButton, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layersCount = label("1", 12, Color.BLACK);
        layersCount.setTypeface(Typeface.DEFAULT_BOLD);
        layersCount.setGravity(Gravity.CENTER);
        layersCount.setBackground(round(Color.WHITE, 2));
        layersCount.setPadding(dp(6), 0, dp(6), 0);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(20), Gravity.CENTER);
        cp.rightMargin = dp(5);
        cp.topMargin = dp(2);
        layersBox.addView(layersCount, cp);

        fullBarItems = new View[] {swapButton, toolButton, brushButton, colorBox, layersBox,
                moreButton, slidersBox};
        View[] order = {swapButton, toolButton, brushButton, colorBox, liteButton, layersBox,
                moreButton};
        for (View v : order) {
            buttonRow.addView(v, new LinearLayout.LayoutParams(0, dp(56), 1f));
        }

        for (View v : order) {
            pressFeedback(v);
        }
        swapButton.setOnClickListener(v -> {
            v.animate().rotationBy(180f).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator()).start();
            setTool(tool == TOOL_ERASER ? TOOL_BRUSH : TOOL_ERASER);
        });
        toolButton.setOnClickListener(v -> showTools());
        brushBox.setOnClickListener(v -> showBrush());
        colorBox.setOnClickListener(v -> showColor(colorBox));
        liteButton.setOnClickListener(v -> setLite(!lite));
        layersBox.setOnClickListener(v -> showLayers(layersBox));
        moreButton.setOnClickListener(v -> {
            dismissPanel();
            host.showMenu(moreButton);
        });
        canvas.setLockedListener(() -> Toast.makeText(act, R.string.layer_locked,
                Toast.LENGTH_SHORT).show());
        syncToolIcons();
        applyBrush();
    }

    /** Buttons shrink a little while pressed and spring back. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void pressFeedback(View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(220)
                            .setInterpolator(new OvershootInterpolator(2.5f)).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private void applyBrush() {
        sizePos = sizeSlider.getValue();
        flowPos = flowSlider.getValue();
        float r = radiusFor(sizePos);
        canvas.setRadius(r);
        canvas.setFlow(flowPos / 100f);
        canvas.setBrushShape(hardness, spacing);
        String txt = String.valueOf(Math.round(r)) + ".0";
        sizeText.setText(txt);
        flowText.setText(String.valueOf(Math.round(flowPos)));
        circleText.setText(txt);
        circle.setBrush(color, Math.min(1f, 0.25f + sizePos / 200f));
        if (brushSizeText != null) {
            brushSizeText.setText(txt + " px");
            brushFlowText.setText(String.valueOf(Math.round(flowPos)) + "%");
            if (brushSizeP.getValue() != sizePos) {
                brushSizeP.setValue(sizePos);
            }
            if (brushFlowP.getValue() != flowPos) {
                brushFlowP.setValue(flowPos);
            }
        }
    }

    private void refreshSwatch() {
        GradientDrawable g = round(0xFF000000 | color, 6);
        g.setStroke(dp(1), Color.WHITE);
        colorSwatch.setBackground(tool == TOOL_ERASER ? new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[] {g, new SlashDrawable()}) : g);
    }

    /** A red diagonal line over the colour while the eraser is active. */
    private static final class SlashDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);

        SlashDrawable() {
            p.setColor(0xFFE53935);
            p.setStrokeWidth(5f);
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            c.drawLine(b.left + 4, b.bottom - 4, b.right - 4, b.top + 4, p);
        }

        @Override
        public void setAlpha(int a) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter f) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    void setLite(boolean on) {
        if (lite == on && liteButton.getTag() != null) {
            return;
        }
        liteButton.setTag(Boolean.TRUE);
        lite = on;
        dismissPanel();
        liteButton.setImageResource(on ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
        final View[] all = new View[fullBarItems.length + 1];
        System.arraycopy(fullBarItems, 0, all, 0, fullBarItems.length);
        all[all.length - 1] = topBar;
        final float lift = dp(18);
        if (on) {
            // Fade the chrome away first, then take it out of the layout in one step so the
            // picture only has to be re-anchored once.
            for (final View v : all) {
                final float dir = v == topBar ? -lift : lift;
                v.animate().alpha(0f).translationY(dir).setDuration(160)
                        .withEndAction(() -> {
                            if (lite) {
                                v.setVisibility(View.GONE);
                                finishLite(true);
                            }
                        }).start();
            }
        } else {
            for (final View v : all) {
                v.animate().cancel();
                v.setAlpha(0f);
                v.setTranslationY(v == topBar ? -lift : lift);
                v.setVisibility(View.VISIBLE);
                v.animate().alpha(1f).translationY(0f).setDuration(220)
                        .setInterpolator(new DecelerateInterpolator()).start();
            }
            finishLite(false);
        }
    }

    private void finishLite(boolean on) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) liteButton.getLayoutParams();
        lp.width = on ? dp(56) : 0;
        lp.weight = on ? 0f : 1f;
        liteButton.setLayoutParams(lp);
        buttonRow.setGravity(on ? Gravity.END | Gravity.CENTER_VERTICAL : Gravity.CENTER_VERTICAL);
        buttonRow.setBackgroundColor(on ? Color.TRANSPARENT : barBg);
    }

    private void setTool(int t) {
        tool = t;
        canvas.setEraser(t == TOOL_ERASER);
        refreshSwatch();
        canvas.setPicker(t == TOOL_PICKER ? rgb -> {
            setColor(rgb, false);
        } : null);
        syncToolIcons();
    }

    private void syncToolIcons() {
        swapButton.setContentDescription(act.getString(tool == TOOL_ERASER ? R.string.brush
                : R.string.eraser));
        toolButton.setImageResource(tool == TOOL_ERASER ? R.drawable.ic_eraser
                : tool == TOOL_PICKER ? R.drawable.ic_picker : R.drawable.ic_brush);
    }

    void setColor(int rgb, boolean fromPanel) {
        color = rgb & 0xFFFFFF;
        canvas.setColor(color);
        refreshSwatch();
        if (circle != null) {
            applyBrush();
        }
        if (!fromPanel && colorSync != null) {
            colorSync.run();
        }
    }

    void updateLayerCount() {
        String n = String.valueOf(canvas.getDocument().getLayerCount());
        if (!n.contentEquals(layersCount.getText())) {
            layersCount.setText(n);
            layersCount.setScaleX(1.5f);
            layersCount.setScaleY(1.5f);
            layersCount.animate().scaleX(1f).scaleY(1f).setDuration(260)
                    .setInterpolator(new OvershootInterpolator(3f)).start();
        } else {
            layersCount.setText(n);
        }
    }

    // ------------------------------------------------------------------ popups

    private void dismissPanel() {
        if (open != null) {
            open.dismiss();
            open = null;
        }
        colorSync = null;
    }

    private PopupWindow show(View content, View anchor, int widthPx) {
        dismissPanel();
        FrameLayout shell = new FrameLayout(act);
        shell.setBackground(round(panelBg, 20));
        shell.setPadding(dp(12), dp(12), dp(12), dp(12));
        shell.addView(content);
        final PopupWindow p = new PopupWindow(shell, widthPx, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        p.setElevation(dp(8));
        p.setBackgroundDrawable(null);
        p.setOutsideTouchable(true);
        p.setOnDismissListener(() -> {
            if (open == p) {
                open = null;
                colorSync = null;
            }
        });
        shell.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int[] at = new int[2];
        buttonRow.getLocationOnScreen(at);
        int y = Math.max(dp(8), at[1] - shell.getMeasuredHeight() - dp(8));
        int screenW = act.getResources().getDisplayMetrics().widthPixels;
        int x = Math.max(dp(8), Math.min(screenW - widthPx - dp(8),
                anchorCenterX(anchor) - widthPx / 2));
        // Slide and fade in from the bar; the Android animation scale setting is respected.
        shell.setAlpha(0f);
        shell.setScaleX(0.92f);
        shell.setScaleY(0.92f);
        shell.setTranslationY(dp(20));
        shell.setPivotX(Math.max(0, Math.min(widthPx, anchorCenterX(anchor) - x)));
        shell.setPivotY(shell.getMeasuredHeight());
        p.showAtLocation(buttonRow, Gravity.TOP | Gravity.START, x, y);
        shell.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(200)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        open = p;
        return p;
    }

    private int anchorCenterX(View a) {
        int[] l = new int[2];
        a.getLocationOnScreen(l);
        return l[0] + a.getWidth() / 2;
    }

    private int panelWidth(float maxDp) {
        int screenW = act.getResources().getDisplayMetrics().widthPixels;
        return Math.min(dp(maxDp), screenW - dp(16));
    }

    // ------------------------------------------------------------------ tools

    private void showTools() {
        GridLayout grid = new GridLayout(act);
        grid.setColumnCount(2);
        Object[][] items = {
            {R.drawable.ic_tool_transform, R.string.tool_transform, -1},
            {R.drawable.ic_tool_wand, R.string.tool_wand, -1},
            {R.drawable.ic_tool_lasso, R.string.tool_lasso, -1},
            {R.drawable.ic_tool_filter, R.string.tool_filter, -1},
            {R.drawable.ic_brush, R.string.brush, TOOL_BRUSH},
            {R.drawable.ic_eraser, R.string.eraser, TOOL_ERASER},
            {R.drawable.ic_tool_smudge, R.string.tool_smudge, -1},
            {R.drawable.ic_tool_blur, R.string.tool_blur, -1},
            {R.drawable.ic_tool_special, R.string.tool_special, -1},
            {R.drawable.ic_tool_bucket, R.string.tool_bucket, -1},
            {R.drawable.ic_tool_vector, R.string.tool_vector, -1},
            {R.drawable.ic_tool_text, R.string.tool_text, -1},
            {R.drawable.ic_tool_frame, R.string.tool_frame, -1},
            {R.drawable.ic_picker, R.string.tool_picker, TOOL_PICKER},
            {R.drawable.ic_tool_canvas, R.string.tool_canvas, -1},
        };
        for (Object[] it : items) {
            final int id = (Integer) it[2];
            LinearLayout cell = new LinearLayout(act);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(2), dp(6), dp(2), dp(6));
            ImageView icon = new ImageView(act);
            icon.setImageResource((Integer) it[0]);
            icon.setImageTintList(ColorStateList.valueOf(id >= 0 && id == tool ? accent : fg));
            cell.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
            TextView name = label(act.getString((Integer) it[1]), 11, id >= 0 && id == tool
                    ? accent : fg);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            cell.addView(name);
            if (id < 0) {
                cell.setAlpha(0.4f);
                cell.setOnClickListener(v -> Toast.makeText(act, R.string.tool_soon,
                        Toast.LENGTH_SHORT).show());
            } else {
                cell.setOnClickListener(v -> {
                    setTool(id);
                    dismissPanel();
                });
            }
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            grid.addView(cell, lp);
        }
        ScrollView scroll = new ScrollView(act);
        scroll.addView(grid);
        int h = act.getResources().getDisplayMetrics().heightPixels / 2;
        FrameLayout box = new FrameLayout(act);
        box.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(dp(8 * 62), h)));
        show(box, toolButton, panelWidth(176));
    }

    // ------------------------------------------------------------------ brush

    private Slider slider(float from, float to, float value) {
        Slider s = new Slider(act);
        s.setValueFrom(from);
        s.setValueTo(to);
        s.setStepSize(1f);
        s.setValue(value);
        s.setLabelBehavior(com.google.android.material.slider.LabelFormatter.LABEL_GONE);
        s.setTickVisible(false);
        if (classic) {
            s.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
            s.setTrackActiveTintList(ColorStateList.valueOf(accent));
            s.setTrackInactiveTintList(ColorStateList.valueOf(0x99FFFFFF));
            s.setHaloTintList(ColorStateList.valueOf(0x33FFFFFF));
        }
        return s;
    }

    private ImageButton stepButton(int icon) {
        ImageButton b = new ImageButton(act);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(fg));
        b.setBackground(round(classic ? 0xFF111111 : 0x22808080, 18));
        b.setScaleType(ImageView.ScaleType.CENTER);
        return b;
    }

    /** One row: value, minus, slider, plus. */
    private LinearLayout sliderRow(TextView value, Slider s) {
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        value.setGravity(Gravity.END);
        row.addView(value, new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
        ImageButton minus = stepButton(R.drawable.ic_minus);
        ImageButton plus = stepButton(R.drawable.ic_add);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(36), dp(36));
        bp.leftMargin = dp(6);
        row.addView(minus, bp);
        row.addView(s, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(36), dp(36));
        row.addView(plus, pp);
        minus.setOnClickListener(v -> s.setValue(Math.max(s.getValueFrom(), s.getValue() - 1)));
        plus.setOnClickListener(v -> s.setValue(Math.min(s.getValueTo(), s.getValue() + 1)));
        return row;
    }

    private TextView brushSizeText;
    private TextView brushFlowText;
    private Slider brushSizeP;
    private Slider brushFlowP;

    /** name, hardness, spacing, default size position, default opacity */
    private static final Object[][] PRESETS = {
        {"Pen (Hard)", 1.0f, 0.08f, 8f, 100f},
        {"Pen (Soft)", 0.5f, 0.10f, 8f, 100f},
        {"Round", 0.8f, 0.15f, 8f, 100f},
        {"Marker", 0.9f, 0.05f, 24f, 100f},
        {"Pen (Fade)", 0.0f, 0.10f, 60f, 100f},
        {"Airbrush", 0.0f, 0.08f, 70f, 30f},
    };

    /** Paints a sample stroke with the real engine, so the list shows what the brush does. */
    private Bitmap strokeSample(Object[] preset, int w, int h) {
        Layer layer = new Layer(w, h, "sample");
        float size = (Float) preset[3];
        float r = Math.max(1.5f, Math.min(size, h * 0.34f));
        nekopaint.core.Brush brush = new nekopaint.core.Brush(0x000000, r, (Float) preset[1], 1f,
                (Float) preset[2], false);
        nekopaint.core.StrokeRasterizer s = new nekopaint.core.StrokeRasterizer(layer, brush);
        int n = 40;
        for (int i = 0; i <= n; i++) {
            float t = i / (float) n;
            s.addPoint(w * 0.06f + t * w * 0.88f,
                    h / 2f + (float) Math.sin(t * Math.PI * 2) * h * 0.16f,
                    (float) Math.max(0.15, Math.sin(t * Math.PI)));
        }
        return unpremultiplied(layer, w, h);
    }

    private static Bitmap unpremultiplied(Layer layer, int w, int h) {
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = layer.getPixel(x, y);
                int a = p >>> 24;
                if (a == 0) {
                    continue;
                }
                if (a == 255) {
                    px[y * w + x] = p;
                } else {
                    int r = Math.min(255, ((p >> 16) & 0xFF) * 255 / a);
                    int g = Math.min(255, ((p >> 8) & 0xFF) * 255 / a);
                    int b = Math.min(255, (p & 0xFF) * 255 / a);
                    px[y * w + x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private void showBrush() {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(label(act.getString(R.string.brush_settings) + " (" + PRESETS.length + ")", 20,
                fg));

        int sampleW = Math.min(dp(360), panelWidth(420) - dp(24));
        final ImageView big = new ImageView(act);
        big.setBackground(round(Color.WHITE, 10));
        big.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(76));
        bp.topMargin = dp(8);
        box.addView(big, bp);
        big.setImageBitmap(strokeSample(PRESETS[brushIndex], sampleW, dp(76)));

        final LinearLayout rows = new LinearLayout(act);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setBackground(round(Color.WHITE, 10));
        final int rowH = dp(60);
        for (int i = 0; i < PRESETS.length; i++) {
            final int idx = i;
            final Object[] pr = PRESETS[i];
            FrameLayout row = new FrameLayout(act);
            row.setBackgroundColor(i == brushIndex ? 0xFFD6E6FB : Color.TRANSPARENT);
            ImageView img = new ImageView(act);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setImageBitmap(strokeSample(pr, sampleW, rowH));
            row.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH));
            TextView name = label((String) pr[0], 13, Color.BLACK);
            name.setPadding(dp(8), 0, 0, dp(4));
            row.addView(name, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START));
            TextView size = label(Math.round(radiusFor((Float) pr[3])) + ".0", 13, Color.BLACK);
            size.setPadding(0, dp(2), dp(10), 0);
            row.addView(size, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));
            row.setOnClickListener(v -> {
                brushIndex = idx;
                hardness = (Float) pr[1];
                spacing = (Float) pr[2];
                sizeSlider.setValue((Float) pr[3]);
                flowSlider.setValue((Float) pr[4]);
                applyBrush();
                big.setImageBitmap(strokeSample(pr, sampleW, dp(76)));
                for (int k = 0; k < rows.getChildCount(); k++) {
                    rows.getChildAt(k).setBackgroundColor(k == idx ? 0xFFD6E6FB : Color.TRANSPARENT);
                }
            });
            rows.addView(row);
        }
        ScrollView list = new ScrollView(act);
        list.addView(rows);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(rowH * PRESETS.length, act.getResources().getDisplayMetrics().heightPixels / 3));
        lp.topMargin = dp(8);
        box.addView(list, lp);

        brushSizeText = label("", 14, fg);
        brushFlowText = label("", 14, fg);
        brushSizeP = slider(1, 150, sizePos);
        brushFlowP = slider(1, 100, flowPos);
        LinearLayout t1 = new LinearLayout(act);
        t1.addView(label(act.getString(R.string.size), 16, fg), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        t1.addView(brushSizeText);
        LinearLayout t2 = new LinearLayout(act);
        t2.addView(label(act.getString(R.string.flow), 16, fg), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        t2.addView(brushFlowText);
        box.addView(t1);
        box.addView(stepRow(brushSizeP));
        box.addView(t2);
        box.addView(stepRow(brushFlowP));
        brushSizeP.addOnChangeListener((sl, v, user) -> {
            if (user) {
                sizeSlider.setValue(v);
            }
        });
        brushFlowP.addOnChangeListener((sl, v, user) -> {
            if (user) {
                flowSlider.setValue(v);
            }
        });
        applyBrush();
        final PopupWindow pw = show(box, brushButton, panelWidth(420));
        pw.setOnDismissListener(() -> {
            if (open == pw) {
                open = null;
                colorSync = null;
            }
            brushSizeText = null;
            brushFlowText = null;
            brushSizeP = null;
            brushFlowP = null;
        });
    }

    /** Minus, slider, plus. */
    private LinearLayout stepRow(Slider s) {
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton minus = stepButton(R.drawable.ic_minus);
        ImageButton plus = stepButton(R.drawable.ic_add);
        row.addView(minus, new LinearLayout.LayoutParams(dp(36), dp(36)));
        row.addView(s, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(plus, new LinearLayout.LayoutParams(dp(36), dp(36)));
        minus.setOnClickListener(v -> s.setValue(Math.max(s.getValueFrom(), s.getValue() - 1)));
        plus.setOnClickListener(v -> s.setValue(Math.min(s.getValueTo(), s.getValue() + 1)));
        s.addOnChangeListener((sl, v, user) -> {
            if (!user) {
                if (s == brushSizeP) {
                    sizeSlider.setValue(v);
                } else if (s == brushFlowP) {
                    flowSlider.setValue(v);
                }
            }
        });
        return row;
    }

    // ------------------------------------------------------------------ colour

    private Runnable colorSync;

    private void showColor(View anchor) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(label(act.getString(R.string.color), 16, fg));
        final ColorWheelView wheel = new ColorWheelView(act);
        int w = Math.min(dp(260), panelWidth(300) - dp(24));
        box.addView(wheel, new LinearLayout.LayoutParams(w, w));
        final TextView hex = label("", 14, fg);
        box.addView(hex);
        final Slider[] ch = new Slider[3];
        final TextView[] val = new TextView[3];
        int[] tints = {0xFFE53935, 0xFF43A047, 0xFF1E88E5};
        for (int i = 0; i < 3; i++) {
            ch[i] = slider(0, 255, 0);
            if (!classic) {
                ch[i].setTrackActiveTintList(ColorStateList.valueOf(tints[i]));
                ch[i].setThumbTintList(ColorStateList.valueOf(tints[i]));
            }
            val[i] = label("0", 14, fg);
            box.addView(sliderRow(val[i], ch[i]));
        }
        final Runnable pull = () -> {
            syncing = true;
            wheel.setColor(color);
            ch[0].setValue(Color.red(color));
            ch[1].setValue(Color.green(color));
            ch[2].setValue(Color.blue(color));
            for (int i = 0; i < 3; i++) {
                val[i].setText(String.valueOf((int) ch[i].getValue()));
            }
            hex.setText(String.format("#%06X", color));
            syncing = false;
        };
        pull.run();
        colorSync = pull;
        wheel.setListener(rgb -> {
            if (!syncing) {
                setColor(rgb, true);
                syncing = true;
                ch[0].setValue(Color.red(rgb));
                ch[1].setValue(Color.green(rgb));
                ch[2].setValue(Color.blue(rgb));
                for (int i = 0; i < 3; i++) {
                    val[i].setText(String.valueOf((int) ch[i].getValue()));
                }
                hex.setText(String.format("#%06X", color));
                syncing = false;
            }
        });
        for (int i = 0; i < 3; i++) {
            ch[i].addOnChangeListener((s, v, user) -> {
                if (syncing) {
                    return;
                }
                setColor(Color.rgb((int) ch[0].getValue(), (int) ch[1].getValue(),
                        (int) ch[2].getValue()), true);
                syncing = true;
                wheel.setColor(color);
                for (int k = 0; k < 3; k++) {
                    val[k].setText(String.valueOf((int) ch[k].getValue()));
                }
                hex.setText(String.format("#%06X", color));
                syncing = false;
            });
        }
        show(box, anchor, panelWidth(300));
    }

    // ------------------------------------------------------------------ layers

    private void showLayers(View anchor) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(label(act.getString(R.string.layers), 16, fg));

        layerRows = new LinearLayout(act);
        layerRows.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(act);
        scroll.addView(layerRows);
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(240)));

        LinearLayout actions = new LinearLayout(act);
        actions.setGravity(Gravity.CENTER);
        actions.addView(actionButton(R.drawable.ic_add, R.string.add_layer, v -> addLayer()), actionLp());
        actions.addView(actionButton(R.drawable.ic_duplicate, R.string.layer_duplicate,
                v -> duplicateLayer()), actionLp());
        actions.addView(actionButton(R.drawable.ic_merge, R.string.layer_merge, v -> mergeLayer()),
                actionLp());
        actions.addView(actionButton(R.drawable.ic_up, R.string.move_up, v -> moveActive(1)), actionLp());
        actions.addView(actionButton(R.drawable.ic_down, R.string.move_down, v -> moveActive(-1)), actionLp());
        actions.addView(actionButton(R.drawable.ic_delete, R.string.delete_layer, v -> deleteLayer()), actionLp());
        ImageButton more = actionButton(R.drawable.ic_more, R.string.layer_more, v -> showLayerMenu(v));
        actions.addView(more, actionLp());
        box.addView(actions);

        layerOpacityText = label("100%", 14, fg);
        layerOpacity = slider(0, 100, 100);
        layerOpacity.addOnChangeListener((s, v, user) -> {
            if (syncing || !user) {
                return;
            }
            canvas.getEditor().setLayerOpacity(canvas.getActiveLayer(), Math.round(v * 255f / 100f));
            layerOpacityText.setText(Math.round(v) + "%");
            canvas.layersChanged();
            host.onLayersEdited();
            rebuildLayerRows();
        });
        blendButton = label("", 14, fg);
        blendButton.setBackground(round(classic ? 0xFFFFFFFF : 0x22808080, 8));
        blendButton.setTextColor(classic ? Color.BLACK : fg);
        blendButton.setPadding(dp(14), dp(10), dp(14), dp(10));
        blendButton.setOnClickListener(v -> {
            PopupMenu m = new PopupMenu(act, v);
            for (int i = 0; i < BLEND_MODES.length; i++) {
                m.getMenu().add(0, i, i, blendName(BLEND_MODES[i]));
            }
            m.setOnMenuItemClickListener(it -> {
                canvas.getEditor().setLayerBlendMode(canvas.getActiveLayer(), BLEND_MODES[it.getItemId()]);
                canvas.layersChanged();
                host.onLayersEdited();
                rebuildLayerRows();
                return true;
            });
            m.show();
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(8);
        box.addView(blendButton, bl);
        box.addView(sliderRowSimple(layerOpacityText, layerOpacity));
        rebuildLayerRows();
        show(box, anchor, panelWidth(340));
    }


    private static final BlendMode[] BLEND_MODES = {
        BlendMode.NORMAL, BlendMode.DARKEN, BlendMode.MULTIPLY, BlendMode.COLOR_BURN,
        BlendMode.LIGHTEN, BlendMode.SCREEN, BlendMode.COLOR_DODGE, BlendMode.ADD,
        BlendMode.OVERLAY, BlendMode.SOFT_LIGHT, BlendMode.HARD_LIGHT, BlendMode.DIFFERENCE,
        BlendMode.EXCLUSION
    };

    private String blendName(BlendMode m) {
        switch (m) {
            case DARKEN: return act.getString(R.string.blend_darken);
            case MULTIPLY: return act.getString(R.string.blend_multiply);
            case COLOR_BURN: return act.getString(R.string.blend_color_burn);
            case LIGHTEN: return act.getString(R.string.blend_lighten);
            case SCREEN: return act.getString(R.string.blend_screen);
            case COLOR_DODGE: return act.getString(R.string.blend_color_dodge);
            case ADD: return act.getString(R.string.blend_add);
            case OVERLAY: return act.getString(R.string.blend_overlay);
            case SOFT_LIGHT: return act.getString(R.string.blend_soft_light);
            case HARD_LIGHT: return act.getString(R.string.blend_hard_light);
            case DIFFERENCE: return act.getString(R.string.blend_difference);
            case EXCLUSION: return act.getString(R.string.blend_exclusion);
            default: return act.getString(R.string.blend_normal);
        }
    }

    private boolean activeLocked() {
        if (canvas.getDocument().getLayer(canvas.getActiveLayer()).isLocked()) {
            Toast.makeText(act, R.string.layer_locked, Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    private void duplicateLayer() {
        Document doc = canvas.getDocument();
        if (doc.getLayerCount() >= 30 || !canvas.isIdle()) {
            return;
        }
        int at = canvas.getActiveLayer();
        canvas.getEditor().duplicateLayer(at);
        canvas.setActiveLayer(at + 1);
        canvas.layerStructureChanged();
        layersEdited();
    }

    private void mergeLayer() {
        int at = canvas.getActiveLayer();
        if (at < 1 || !canvas.isIdle()) {
            return;
        }
        if (canvas.getDocument().getLayer(at - 1).isLocked()) {
            Toast.makeText(act, R.string.layer_locked, Toast.LENGTH_SHORT).show();
            return;
        }
        canvas.getEditor().mergeDown(at);
        canvas.setActiveLayer(at - 1);
        canvas.layerStructureChanged();
        layersEdited();
    }

    private void showLayerMenu(View anchor) {
        final int index = canvas.getActiveLayer();
        final Layer layer = canvas.getDocument().getLayer(index);
        PopupMenu m = new PopupMenu(act, anchor);
        m.getMenu().add(0, 1, 0, R.string.layer_rename);
        m.getMenu().add(0, 2, 1, layer.isLocked() ? R.string.layer_unlock : R.string.layer_lock);
        m.getMenu().add(0, 3, 2, R.string.layer_clear);
        m.getMenu().add(0, 4, 3, R.string.layer_flip_h);
        m.getMenu().add(0, 5, 4, R.string.layer_flip_v);
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1:
                    renameLayer(layer);
                    return true;
                case 2:
                    layer.setLocked(!layer.isLocked());
                    rebuildLayerRows();
                    return true;
                default:
                    break;
            }
            if (layer.isLocked()) {
                Toast.makeText(act, R.string.layer_locked, Toast.LENGTH_SHORT).show();
                return true;
            }
            if (!canvas.isIdle()) {
                return true;
            }
            if (it.getItemId() == 3) {
                canvas.getEditor().clearLayer(index);
            } else {
                canvas.getEditor().flipLayer(index, it.getItemId() == 4);
            }
            canvas.layerPixelsEdited();
            layersEdited();
            return true;
        });
        m.show();
    }

    private void renameLayer(final Layer layer) {
        final android.widget.EditText input = new android.widget.EditText(act);
        input.setSingleLine(true);
        input.setText(layer.getName());
        input.setSelection(input.getText().length());
        FrameLayout wrap = new FrameLayout(act);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(input);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
                .setTitle(R.string.layer_name)
                .setView(wrap)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String n = input.getText().toString().trim();
                    if (!n.isEmpty()) {
                        layer.setName(n.length() > 40 ? n.substring(0, 40) : n);
                        rebuildLayerRows();
                        host.onLayersEdited();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private LinearLayout sliderRowSimple(TextView value, Slider s) {
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(value, new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(s, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private LinearLayout.LayoutParams actionLp() {
        return new LinearLayout.LayoutParams(0, dp(48), 1f);
    }

    private ImageButton actionButton(int icon, int desc, View.OnClickListener l) {
        ImageButton b = iconButton(icon, desc);
        b.setOnClickListener(l);
        return b;
    }

    private void addLayer() {
        Document doc = canvas.getDocument();
        if (doc.getLayerCount() >= 30 || !canvas.isIdle()) {
            return;
        }
        int at = canvas.getActiveLayer() + 1;
        canvas.getEditor().addLayer("Layer " + (doc.getLayerCount() + 1), at);
        canvas.setActiveLayer(at);
        canvas.layerStructureChanged();
        layersEdited();
    }

    private void deleteLayer() {
        Document doc = canvas.getDocument();
        if (doc.getLayerCount() <= 1 || !canvas.isIdle()) {
            return;
        }
        int index = canvas.getActiveLayer();
        canvas.getEditor().removeLayer(index);
        canvas.setActiveLayer(Math.max(0, index - 1));
        canvas.layerStructureChanged();
        layersEdited();
    }

    private void moveActive(int delta) {
        Document doc = canvas.getDocument();
        int from = canvas.getActiveLayer();
        int to = from + delta;
        if (to < 0 || to >= doc.getLayerCount() || !canvas.isIdle()) {
            return;
        }
        canvas.getEditor().moveLayer(from, to);
        canvas.setActiveLayer(to);
        canvas.layerStructureChanged();
        layersEdited();
    }

    private void layersEdited() {
        host.onLayersEdited();
        rebuildLayerRows();
    }

    /** Called by the activity when the layer list changed from outside. */
    void refreshLayers() {
        updateLayerCount();
        if (layerRows != null && open != null) {
            rebuildLayerRows();
        }
    }

    private static Bitmap thumb(Layer layer, int size) {
        int w = layer.getWidth();
        int h = layer.getHeight();
        int tw = w >= h ? size : Math.max(1, size * w / h);
        int th = h >= w ? size : Math.max(1, size * h / w);
        int[] px = new int[tw * th];
        for (int y = 0; y < th; y++) {
            int sy = Math.min(h - 1, (int) ((y + 0.5f) * h / th));
            for (int x = 0; x < tw; x++) {
                int sx = Math.min(w - 1, (int) ((x + 0.5f) * w / tw));
                int p = layer.getPixel(sx, sy);
                int a = p >>> 24;
                if (a == 0) {
                    px[y * tw + x] = 0;
                } else if (a == 255) {
                    px[y * tw + x] = p;
                } else {
                    int r = Math.min(255, ((p >> 16) & 0xFF) * 255 / a);
                    int g = Math.min(255, ((p >> 8) & 0xFF) * 255 / a);
                    int b = Math.min(255, (p & 0xFF) * 255 / a);
                    px[y * tw + x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
        }
        return Bitmap.createBitmap(px, tw, th, Bitmap.Config.ARGB_8888);
    }

    private void rebuildLayerRows() {
        if (layerRows == null) {
            return;
        }
        final Document doc = canvas.getDocument();
        updateLayerCount();
        layerRows.removeAllViews();
        for (int i = doc.getLayerCount() - 1; i >= 0; i--) {
            final int index = i;
            final Layer layer = doc.getLayer(i);
            LinearLayout row = new LinearLayout(act);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6), dp(4), dp(6), dp(4));
            if (i == canvas.getActiveLayer()) {
                row.setBackground(round(rowSelected, 10));
            }
            ImageView t = new ImageView(act);
            t.setBackground(round(0xFFDDDDDD, 6));
            t.setImageBitmap(thumb(layer, dp(44)));
            t.setPadding(dp(2), dp(2), dp(2), dp(2));
            row.addView(t, new LinearLayout.LayoutParams(dp(48), dp(48)));

            LinearLayout text = new LinearLayout(act);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(dp(10), 0, dp(6), 0);
            TextView name = label(layer.getName(), 14, fg);
            name.setSingleLine(true);
            text.addView(name);
            String mode = blendName(layer.getBlendMode());
            text.addView(label(Math.round(layer.getOpacity() * 100f / 255f) + "%  " + mode, 12, fgDim));
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (layer.isLocked()) {
                ImageView lock = new ImageView(act);
                lock.setImageResource(R.drawable.ic_lock);
                lock.setImageTintList(ColorStateList.valueOf(fgDim));
                row.addView(lock, new LinearLayout.LayoutParams(dp(20), dp(20)));
            }
            ImageButton eye = iconButton(layer.isVisible() ? R.drawable.ic_eye : R.drawable.ic_eye_off,
                    R.string.visibility);
            eye.setOnClickListener(v -> {
                canvas.getEditor().setLayerVisible(index, !layer.isVisible());
                canvas.layersChanged();
                host.onLayersEdited();
                rebuildLayerRows();
            });
            row.addView(eye, new LinearLayout.LayoutParams(dp(44), dp(44)));
            row.setOnClickListener(v -> {
                canvas.setActiveLayer(index);
                rebuildLayerRows();
            });
            row.setOnLongClickListener(v -> {
                canvas.setActiveLayer(index);
                rebuildLayerRows();
                showLayerMenu(v);
                return true;
            });
            layerRows.addView(row);
        }
        Layer active = doc.getLayer(canvas.getActiveLayer());
        syncing = true;
        layerOpacity.setValue(Math.round(active.getOpacity() * 100f / 255f));
        layerOpacityText.setText(Math.round(active.getOpacity() * 100f / 255f) + "%");
        blendButton.setText(blendName(active.getBlendMode()));
        syncing = false;
    }
}
