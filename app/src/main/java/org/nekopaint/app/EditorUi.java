// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
    private ImageButton swapButton;
    private ImageButton toolButton;
    private ImageButton brushButton;
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
        bar.setBackgroundColor(barBg);
        buildBar();
        canvas.setRadius(radiusFor(sizePos));
        canvas.setFlow(flowPos / 100f);
        canvas.setColor(color);
        refreshSwatch();
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
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        swapButton = iconButton(R.drawable.ic_eraser, R.string.eraser);
        toolButton = iconButton(R.drawable.ic_brush, R.string.tools);
        brushButton = iconButton(R.drawable.ic_brush_pattern, R.string.brush_settings);
        liteButton = iconButton(R.drawable.ic_expand_more, R.string.lite_mode);
        moreButton = iconButton(R.drawable.ic_chevron_left, R.string.menu);
        layersButton = iconButton(R.drawable.ic_layers, R.string.layers);

        FrameLayout colorBox = new FrameLayout(act);
        colorSwatch = new View(act);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER);
        colorBox.addView(colorSwatch, sp);
        colorBox.setContentDescription(act.getString(R.string.color));

        FrameLayout layersBox = new FrameLayout(act);
        layersBox.addView(layersButton, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layersCount = label("1", 10, fg);
        layersCount.setTypeface(Typeface.DEFAULT_BOLD);
        layersCount.setGravity(Gravity.CENTER);
        layersCount.setBackground(round(accent, 8));
        layersCount.setPadding(dp(5), 0, dp(5), 0);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(16), Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        cp.bottomMargin = dp(4);
        cp.leftMargin = dp(20);
        layersBox.addView(layersCount, cp);

        fullBarItems = new View[] {swapButton, toolButton, brushButton, colorBox, layersBox,
                moreButton};
        View[] order = {swapButton, toolButton, brushButton, colorBox, liteButton, layersBox,
                moreButton};
        for (View v : order) {
            bar.addView(v, new LinearLayout.LayoutParams(0, dp(56), 1f));
        }

        swapButton.setOnClickListener(v -> setTool(tool == TOOL_ERASER ? TOOL_BRUSH : TOOL_ERASER));
        toolButton.setOnClickListener(v -> showTools());
        brushButton.setOnClickListener(v -> showBrush());
        colorBox.setOnClickListener(v -> showColor(colorBox));
        liteButton.setOnClickListener(v -> setLite(!lite));
        layersBox.setOnClickListener(v -> showLayers(layersBox));
        moreButton.setOnClickListener(v -> {
            dismissPanel();
            host.showMenu(moreButton);
        });
        syncToolIcons();
    }

    private void refreshSwatch() {
        GradientDrawable g = round(0xFF000000 | color, 6);
        g.setStroke(dp(2), fg);
        colorSwatch.setBackground(g);
    }

    void setLite(boolean on) {
        lite = on;
        dismissPanel();
        topBar.setVisibility(on ? View.GONE : View.VISIBLE);
        for (View v : fullBarItems) {
            v.setVisibility(on ? View.GONE : View.VISIBLE);
        }
        liteButton.setImageResource(on ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) liteButton.getLayoutParams();
        lp.width = on ? dp(56) : 0;
        lp.weight = on ? 0f : 1f;
        liteButton.setLayoutParams(lp);
        bar.setGravity(on ? Gravity.END | Gravity.CENTER_VERTICAL : Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(on ? Color.TRANSPARENT : barBg);
    }

    private void setTool(int t) {
        tool = t;
        canvas.setEraser(t == TOOL_ERASER);
        canvas.setPicker(t == TOOL_PICKER ? rgb -> {
            setColor(rgb, false);
        } : null);
        syncToolIcons();
    }

    private void syncToolIcons() {
        swapButton.setImageResource(tool == TOOL_ERASER ? R.drawable.ic_brush : R.drawable.ic_eraser);
        swapButton.setContentDescription(act.getString(tool == TOOL_ERASER ? R.string.brush
                : R.string.eraser));
        toolButton.setImageResource(tool == TOOL_ERASER ? R.drawable.ic_eraser
                : tool == TOOL_PICKER ? R.drawable.ic_picker : R.drawable.ic_brush);
    }

    void setColor(int rgb, boolean fromPanel) {
        color = rgb & 0xFFFFFF;
        canvas.setColor(color);
        refreshSwatch();
        if (!fromPanel && colorSync != null) {
            colorSync.run();
        }
    }

    void updateLayerCount() {
        layersCount.setText(String.valueOf(canvas.getDocument().getLayerCount()));
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
        bar.getLocationOnScreen(at);
        int y = Math.max(dp(8), at[1] - shell.getMeasuredHeight() - dp(8));
        int screenW = act.getResources().getDisplayMetrics().widthPixels;
        int x = Math.max(dp(8), Math.min(screenW - widthPx - dp(8),
                anchorCenterX(anchor) - widthPx / 2));
        p.showAtLocation(bar, Gravity.TOP | Gravity.START, x, y);
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
            cell.setPadding(dp(4), dp(8), dp(4), dp(8));
            ImageView icon = new ImageView(act);
            icon.setImageResource((Integer) it[0]);
            icon.setImageTintList(ColorStateList.valueOf(id >= 0 && id == tool ? accent : fg));
            cell.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
            TextView name = label(act.getString((Integer) it[1]), 12, id >= 0 && id == tool
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
                Math.min(dp(8 * 76), h)));
        show(box, toolButton, panelWidth(280));
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

    private void showBrush() {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(label(act.getString(R.string.brush_settings), 16, fg));

        BrushPreview preview = new BrushPreview(act, null);
        box.addView(preview, new LinearLayout.LayoutParams(dp(64), dp(64)));

        final TextView sizeText = label("", 14, fg);
        final TextView flowText = label("", 14, fg);
        final Slider size = slider(1, 150, sizePos);
        final Slider flow = slider(1, 100, flowPos);
        box.addView(label(act.getString(R.string.size), 12, fgDim));
        box.addView(sliderRow(sizeText, size));
        box.addView(label(act.getString(R.string.flow), 12, fgDim));
        box.addView(sliderRow(flowText, flow));

        Runnable apply = () -> {
            sizePos = size.getValue();
            flowPos = flow.getValue();
            float r = radiusFor(sizePos);
            canvas.setRadius(r);
            canvas.setFlow(flowPos / 100f);
            sizeText.setText(String.valueOf(Math.round(r)));
            flowText.setText(String.valueOf(Math.round(flowPos)));
            preview.setBrush(color, sizePos / 150f);
        };
        size.addOnChangeListener((s, v, user) -> apply.run());
        flow.addOnChangeListener((s, v, user) -> apply.run());
        apply.run();
        show(box, brushButton, panelWidth(340));
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
        actions.addView(actionButton(R.drawable.ic_up, R.string.move_up, v -> moveActive(1)), actionLp());
        actions.addView(actionButton(R.drawable.ic_down, R.string.move_down, v -> moveActive(-1)), actionLp());
        actions.addView(actionButton(R.drawable.ic_delete, R.string.delete_layer, v -> deleteLayer()), actionLp());
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
            m.getMenu().add(0, 0, 0, R.string.blend_normal);
            m.getMenu().add(0, 1, 1, R.string.blend_multiply);
            m.getMenu().add(0, 2, 2, R.string.blend_screen);
            m.setOnMenuItemClickListener(it -> {
                BlendMode mode = it.getItemId() == 1 ? BlendMode.MULTIPLY
                        : it.getItemId() == 2 ? BlendMode.SCREEN : BlendMode.NORMAL;
                canvas.getEditor().setLayerBlendMode(canvas.getActiveLayer(), mode);
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

    private LinearLayout sliderRowSimple(TextView value, Slider s) {
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(value, new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(s, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private LinearLayout.LayoutParams actionLp() {
        return new LinearLayout.LayoutParams(dp(52), dp(48));
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
        int[] src = layer.pixels();
        int[] px = new int[tw * th];
        for (int y = 0; y < th; y++) {
            int sy = Math.min(h - 1, (int) ((y + 0.5f) * h / th));
            for (int x = 0; x < tw; x++) {
                int sx = Math.min(w - 1, (int) ((x + 0.5f) * w / tw));
                int p = src[sy * w + sx];
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
            String mode = layer.getBlendMode() == BlendMode.MULTIPLY ? act.getString(R.string.blend_multiply)
                    : layer.getBlendMode() == BlendMode.SCREEN ? act.getString(R.string.blend_screen)
                    : act.getString(R.string.blend_normal);
            text.addView(label(Math.round(layer.getOpacity() * 100f / 255f) + "%  " + mode, 12, fgDim));
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

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
            layerRows.addView(row);
        }
        Layer active = doc.getLayer(canvas.getActiveLayer());
        syncing = true;
        layerOpacity.setValue(Math.round(active.getOpacity() * 100f / 255f));
        layerOpacityText.setText(Math.round(active.getOpacity() * 100f / 255f) + "%");
        blendButton.setText(active.getBlendMode() == BlendMode.MULTIPLY ? R.string.blend_multiply
                : active.getBlendMode() == BlendMode.SCREEN ? R.string.blend_screen
                : R.string.blend_normal);
        syncing = false;
    }
}
