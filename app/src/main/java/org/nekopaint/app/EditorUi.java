// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
    private ImageButton clipButton;
    private TextView alphaButton;
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
        canvas.setQuickPicker(rgb -> setColor(rgb, false));
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

    private View openShell;

    private void dismissPanel() {
        dismissPanel(true);
    }

    /** Closes the open panel; with [animate] it fades and shrinks towards the bar first. */
    private void dismissPanel(boolean animate) {
        colorSync = null;
        final PopupWindow p = open;
        final View shell = openShell;
        open = null;
        openShell = null;
        if (p == null) {
            return;
        }
        if (!animate || shell == null || !p.isShowing()) {
            p.dismiss();
            return;
        }
        shell.animate().cancel();
        shell.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f)
                .translationY(shell.getPivotY() == 0f ? -dp(16) : dp(16)).setDuration(150)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.4f))
                .withEndAction(() -> {
                    try {
                        p.dismiss();
                    } catch (RuntimeException ignored) {
                        // the window is already gone
                    }
                }).start();
    }

    private PopupWindow show(View content, View anchor, int widthPx) {
        return show(content, anchor, widthPx, false);
    }

    /** [fromTop]: the panel hangs under the top bar instead of standing on the bottom bar. */
    private PopupWindow show(View content, View anchor, int widthPx, boolean fromTop) {
        dismissPanel(false);
        FrameLayout shell = new FrameLayout(act);
        shell.setBackground(round(panelBg, 20));
        shell.setPadding(dp(12), dp(12), dp(12), dp(12));
        shell.addView(content);
        final PopupWindow p = new PopupWindow(shell, widthPx, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        p.setElevation(dp(8));
        p.setBackgroundDrawable(null);
        p.setOutsideTouchable(true);
        // A tap outside closes the panel with the same animation as the other ways of closing it.
        p.setTouchInterceptor((v, ev) -> {
            boolean outside = ev.getAction() == MotionEvent.ACTION_OUTSIDE
                    || (ev.getAction() == MotionEvent.ACTION_DOWN
                    && (ev.getX() < 0 || ev.getY() < 0 || ev.getX() > v.getWidth() || ev.getY() > v.getHeight()));
            if (outside && open == p) {
                dismissPanel();
                return true;
            }
            return false;
        });
        p.setOnDismissListener(() -> {
            if (open == p) {
                open = null;
                openShell = null;
                colorSync = null;
            }
        });
        shell.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int[] at = new int[2];
        buttonRow.getLocationOnScreen(at);
        int y = Math.max(dp(8), at[1] - shell.getMeasuredHeight() - dp(8));
        if (fromTop) {
            int[] tb = new int[2];
            topBar.getLocationOnScreen(tb);
            y = tb[1] + topBar.getHeight() + dp(4);
        }
        int screenW = act.getResources().getDisplayMetrics().widthPixels;
        int x = Math.max(dp(8), Math.min(screenW - widthPx - dp(8),
                anchorCenterX(anchor) - widthPx / 2));
        // Slide and fade in from the bar; the Android animation scale setting is respected.
        shell.setAlpha(0f);
        shell.setScaleX(0.92f);
        shell.setScaleY(0.92f);
        shell.setTranslationY(fromTop ? -dp(20) : dp(20));
        shell.setPivotX(Math.max(0, Math.min(widthPx, anchorCenterX(anchor) - x)));
        shell.setPivotY(fromTop ? 0f : shell.getMeasuredHeight());
        p.showAtLocation(buttonRow, Gravity.TOP | Gravity.START, x, y);
        shell.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(200)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        open = p;
        openShell = shell;
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

    // ------------------------------------------------------------------ toggles and notices

    private TextView notice;
    private final Runnable hideNotice = () -> {
        if (notice != null) {
            notice.animate().alpha(0f).translationY(-dp(8)).setDuration(220).start();
        }
    };

    /** The pill at the top that says what an undo or redo just did. */
    void showHistoryNotice(boolean undo, String kind) {
        FrameLayout root = act.findViewById(R.id.root);
        if (root == null) {
            return;
        }
        if (notice == null) {
            notice = new TextView(act);
            notice.setTextColor(Color.WHITE);
            notice.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            notice.setGravity(Gravity.CENTER_VERTICAL);
            notice.setCompoundDrawablePadding(dp(10));
            notice.setPadding(dp(16), dp(10), dp(18), dp(10));
            notice.setBackground(round(0xE6000000, 24));
            notice.setElevation(dp(6));
            notice.setClickable(false);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            root.addView(notice, lp);
        }
        int what = Editor_KIND_erase.equals(kind) ? R.string.act_erase
                : Editor_KIND_clear.equals(kind) ? R.string.act_clear
                : Editor_KIND_flip.equals(kind) ? R.string.act_flip
                : nekopaint.core.Editor.KIND_LAYER.equals(kind) ? R.string.act_layer : R.string.act_stroke;
        notice.setText(act.getString(undo ? R.string.undo_fmt : R.string.redo_fmt, act.getString(what)));
        android.graphics.drawable.Drawable icon = act.getDrawable(undo ? R.drawable.ic_undo : R.drawable.ic_redo).mutate();
        icon.setTint(Color.WHITE);
        icon.setBounds(0, 0, dp(22), dp(22));
        notice.setCompoundDrawablesRelative(icon, null, null, null);
        ((FrameLayout.LayoutParams) notice.getLayoutParams()).topMargin = topBar.getBottom() + dp(8);
        notice.requestLayout();
        notice.removeCallbacks(hideNotice);
        notice.animate().cancel();
        notice.setTranslationY(-dp(10));
        notice.setAlpha(0f);
        notice.animate().alpha(1f).translationY(0f).setDuration(160)
                .setInterpolator(new DecelerateInterpolator()).start();
        notice.postDelayed(hideNotice, 1300);
    }

    private static final String Editor_KIND_erase = nekopaint.core.Editor.KIND_ERASE;
    private static final String Editor_KIND_clear = nekopaint.core.Editor.KIND_CLEAR;
    private static final String Editor_KIND_flip = nekopaint.core.Editor.KIND_FLIP;

    /** The switches button next to redo: grid, how the picture looks when zoomed, palm rejection. */
    void showToggles(View anchor) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(switchRow(R.string.tg_grid, Opts.flag(act, Opts.GRID), on -> {
            Opts.setFlag(act, Opts.GRID, on);
            canvas.applySettings();
        }));
        TextView gs = label(act.getString(R.string.grid_settings) + "  \u203A", 15, accent);
        gs.setPadding(dp(4), dp(10), dp(4), dp(10));
        gs.setOnClickListener(v -> showGridSettings(anchor));
        box.addView(gs);

        TextView zoom = label(act.getString(R.string.tg_zoom), 15, fg);
        zoom.setPadding(dp(4), dp(12), 0, dp(6));
        box.addView(zoom);
        com.google.android.material.button.MaterialButtonToggleGroup g =
                new com.google.android.material.button.MaterialButtonToggleGroup(act);
        g.setSingleSelection(true);
        g.setSelectionRequired(true);
        boolean smooth = Opts.flag(act, Opts.SMOOTH_ZOOM);
        int[] names = {R.string.tg_smooth, R.string.tg_pixel};
        for (int i = 0; i < 2; i++) {
            com.google.android.material.button.MaterialButton b = new com.google.android.material.button.MaterialButton(
                    act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setId(View.generateViewId());
            b.setText(names[i]);
            b.setTextColor(fg);
            g.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            final boolean isSmooth = i == 0;
            if (isSmooth == smooth) {
                g.check(b.getId());
            }
            b.addOnCheckedChangeListener((btn, on) -> {
                if (on) {
                    Opts.setFlag(act, Opts.SMOOTH_ZOOM, isSmooth);
                    canvas.applySettings();
                }
            });
        }
        box.addView(g);

        LinearLayout stylus = switchRow(R.string.tg_stylus, Opts.num(act, Opts.PALM) == 1, on -> {
            Opts.setNum(act, Opts.PALM, on ? 1 : 0);
            canvas.applySettings();
        });
        stylus.setPadding(0, dp(10), 0, 0);
        box.addView(stylus);
        show(box, anchor, panelWidth(320), true);
    }

    private static final int[] GRID_COLORS = {
        0x80808080, 0xCC808080, 0xFF000000, 0xFFFFFFFF, 0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFDD835
    };

    private void gridSlider(LinearLayout box, int title, String key, int from, int to, boolean px) {
        int cur = Math.max(from, Math.min(to, Opts.num(act, key)));
        TextView t = label("", 14, fg);
        t.setText(act.getString(title) + ": " + cur);
        t.setPadding(dp(4), dp(8), 0, 0);
        Slider sl = slider(from, to, cur);
        sl.addOnChangeListener((x, v, user) -> {
            t.setText(act.getString(title) + ": " + (int) v);
            if (user) {
                Opts.setNum(act, key, (int) v);
                canvas.applySettings();
            }
        });
        box.addView(t);
        box.addView(sl);
    }

    private void gridColors(LinearLayout box, int title, String key) {
        TextView t = label(act.getString(title), 14, fg);
        t.setPadding(dp(4), dp(8), 0, dp(4));
        box.addView(t);
        LinearLayout row = new LinearLayout(act);
        int cur = Opts.num(act, key);
        for (int c : GRID_COLORS) {
            View sw = new View(act);
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            d.setColor(c | 0xFF000000);
            d.setStroke(dp(c == cur ? 3 : 1), c == cur ? accent : 0x66808080);
            sw.setBackground(d);
            sw.setOnClickListener(v -> {
                Opts.setNum(act, key, c);
                canvas.applySettings();
                showGridSettings(gridAnchor);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
            lp.rightMargin = dp(8);
            row.addView(sw, lp);
        }
        box.addView(row);
    }

    /** The grid panel, with the same options as ibis Paint: two intervals, two colours, position. */
    private View gridAnchor;

    void showGridSettings(View anchor) {
        gridAnchor = anchor;
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(label(act.getString(R.string.grid_settings), 16, fg));
        box.addView(switchRow(R.string.grid_show, Opts.flag(act, Opts.GRID), on -> {
            Opts.setFlag(act, Opts.GRID, on);
            canvas.applySettings();
        }));
        gridSlider(box, R.string.grid_1, Opts.GRID_SIZE, 1, 128, true);
        gridSlider(box, R.string.grid_2, Opts.GRID2, 1, 16, false);
        gridColors(box, R.string.grid_c1, Opts.GRID_C1);
        gridColors(box, R.string.grid_c2, Opts.GRID_C2);
        gridSlider(box, R.string.grid_x, Opts.GRID_X, 0, 128, true);
        gridSlider(box, R.string.grid_y, Opts.GRID_Y, 0, 128, true);
        TextView reset = label(act.getString(R.string.grid_reset), 15, accent);
        reset.setPadding(dp(4), dp(12), dp(4), dp(8));
        reset.setOnClickListener(v -> {
            Opts.setNum(act, Opts.GRID_SIZE, 16);
            Opts.setNum(act, Opts.GRID2, 4);
            Opts.setNum(act, Opts.GRID_C1, 0x80808080);
            Opts.setNum(act, Opts.GRID_C2, 0xCC808080);
            Opts.setNum(act, Opts.GRID_X, 0);
            Opts.setNum(act, Opts.GRID_Y, 0);
            canvas.applySettings();
            showGridSettings(anchor);
        });
        box.addView(reset);
        ScrollView sv = new ScrollView(act);
        sv.addView(box);
        show(sv, anchor, panelWidth(320), true);
    }

    /** One of the four squares that choose how transparent pixels look. */
    private View paperSwatch(final int mode) {
        final boolean sel = Opts.num(act, Opts.PAPER) == mode;
        View v = new View(act) {
            @Override
            protected void onDraw(Canvas c) {
                int w = getWidth();
                int h = getHeight();
                android.graphics.Paint p = new android.graphics.Paint();
                if (mode == 0) {
                    p.setColor(Color.WHITE);
                    c.drawRect(0, 0, w, h, p);
                } else if (mode == 1 || mode == 2) {
                    int n = 4;
                    for (int y = 0; y < n; y++) {
                        for (int x = 0; x < n; x++) {
                            boolean a = (x + y) % 2 == 0;
                            p.setColor(mode == 1 ? (a ? 0xFFFFFFFF : 0xFFD2D2D2) : (a ? 0xFF707070 : 0xFF4A4A4A));
                            c.drawRect(x * w / (float) n, y * h / (float) n, (x + 1) * w / (float) n,
                                    (y + 1) * h / (float) n, p);
                        }
                    }
                } else {
                    p.setColor(0xFFEEEEEE);
                    c.drawRect(0, 0, w, h, p);
                    p.setColor(0xFFCC3333);
                    p.setStrokeWidth(dp(2));
                    c.drawLine(0, h, w, 0, p);
                }
                android.graphics.Paint b = new android.graphics.Paint();
                b.setStyle(android.graphics.Paint.Style.STROKE);
                b.setStrokeWidth(dp(sel ? 3 : 1));
                b.setColor(sel ? 0xFF2196F3 : 0xFF888888);
                c.drawRect(dp(1), dp(1), w - dp(1), h - dp(1), b);
            }
        };
        v.setContentDescription(act.getString(R.string.paper_hint));
        v.setOnClickListener(x -> {
            Opts.setNum(act, Opts.PAPER, mode);
            canvas.applySettings();
            if (paperRow != null) {
                paperRow.removeAllViews();
                fillPaperRow();
            }
        });
        return v;
    }

    private LinearLayout paperRow;

    private void fillPaperRow() {
        for (int m = 0; m < 4; m++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
            lp.rightMargin = dp(8);
            paperRow.addView(paperSwatch(m), lp);
        }
    }

    private LinearLayout switchRow(int text, boolean on, java.util.function.Consumer<Boolean> change) {
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = label(act.getString(text), 15, fg);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        com.google.android.material.materialswitch.MaterialSwitch sw =
                new com.google.android.material.materialswitch.MaterialSwitch(act);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((b, v) -> change.accept(v));
        row.addView(sw);
        row.setPadding(dp(4), dp(2), 0, dp(2));
        return row;
    }

    // ------------------------------------------------------------------ transform

    private TransformTool tf;
    private LinearLayout tfPanel;
    private LinearLayout tfContent;
    private int tfLayer = -1;
    private int[] tfOriginal;
    private boolean tfNewLayer;
    private boolean tfSyncing;
    private final java.util.ArrayList<Runnable> tfSync = new java.util.ArrayList<>();

    boolean isTransforming() {
        return tf != null;
    }

    /** The move button of the layers panel: lifts the layer's picture and lets you shape it. */
    private void startLayerTransform() {
        if (tf != null || !canvas.isIdle()) {
            return;
        }
        final Document doc = canvas.getDocument();
        final int idx = canvas.getActiveLayer();
        final Layer l = doc.getLayer(idx);
        if (l.isLocked() || l.isSelection()) {
            Toast.makeText(act, R.string.layer_locked, Toast.LENGTH_SHORT).show();
            return;
        }
        int w = l.getWidth();
        int h = l.getHeight();
        int[] px = l.copyPixels();
        int minX = w;
        int minY = h;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) != 0) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) {
            Toast.makeText(act, R.string.transform_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        int cw = maxX - minX + 1;
        int ch = maxY - minY + 1;
        int[] crop = new int[cw * ch];
        for (int y = 0; y < ch; y++) {
            System.arraycopy(px, (minY + y) * w + minX, crop, y * cw, cw);
        }
        CanvasView.unpremultiply(crop, crop.length);
        Bitmap bmp = Bitmap.createBitmap(crop, cw, ch, Bitmap.Config.ARGB_8888);
        tfOriginal = px;
        tfNewLayer = false;
        tfLayer = idx;
        l.pasteRect(new nekopaint.core.Rect(0, 0, w, h), new int[w * h]);
        canvas.layersChanged();
        beginTransform(bmp, minX, minY);
    }

    /** After importing a picture: it is already on a new empty layer, shape it before keeping it. */
    void startImageTransform(Bitmap bmp) {
        if (tf != null) {
            return;
        }
        Document doc = canvas.getDocument();
        tfLayer = canvas.getActiveLayer();
        tfOriginal = doc.getLayer(tfLayer).copyPixels();
        tfNewLayer = true;
        beginTransform(bmp, (doc.getWidth() - bmp.getWidth()) / 2f, (doc.getHeight() - bmp.getHeight()) / 2f);
    }

    private void beginTransform(Bitmap bmp, float left, float top) {
        dismissPanel(false);
        Document doc = canvas.getDocument();
        tf = new TransformTool(bmp, left, top, doc.getWidth(), doc.getHeight());
        tf.setListener(this::transformChanged);
        canvas.setTransform(tf);
        buildTransformPanel();
        Toast.makeText(act, R.string.transform_hint, Toast.LENGTH_SHORT).show();
    }

    private void transformChanged() {
        canvas.invalidate();
        tfSyncing = true;
        for (Runnable r : tfSync) {
            r.run();
        }
        tfSyncing = false;
    }

    private void buildTransformPanel() {
        FrameLayout root = act.findViewById(R.id.root);
        tfPanel = new LinearLayout(act);
        tfPanel.setOrientation(LinearLayout.HORIZONTAL);
        tfPanel.setBackground(round(panelBg, 20));
        tfPanel.setPadding(dp(8), dp(8), dp(8), dp(8));
        tfPanel.setElevation(dp(8));

        LinearLayout main = new LinearLayout(act);
        main.setOrientation(LinearLayout.VERTICAL);
        LinearLayout tabs = new LinearLayout(act);
        tabs.setBackground(round(classic ? 0xFF000000 : 0x22808080, 8));
        final int[] names = {R.string.transform_scale, R.string.transform_perspective,
            R.string.transform_mesh, R.string.transform_custom};
        final TextView[] tabViews = new TextView[4];
        for (int i = 0; i < 4; i++) {
            final int mode = i;
            TextView t = label(act.getString(names[i]), 14, i == tf.mode ? accent : fg);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(4), dp(10), dp(4), dp(10));
            t.setOnClickListener(v -> {
                tf.setMode(mode);
                for (int k = 0; k < 4; k++) {
                    tabViews[k].setTextColor(k == mode ? accent : fg);
                }
                fillTransformContent();
            });
            tabViews[i] = t;
            tabs.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        main.addView(tabs);
        tfContent = new LinearLayout(act);
        tfContent.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(act);
        sv.addView(tfContent);
        main.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                act.getResources().getDisplayMetrics().heightPixels / 4));
        tfPanel.addView(main, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout side = new LinearLayout(act);
        side.setOrientation(LinearLayout.VERTICAL);
        ImageButton ok = iconButton(R.drawable.ic_check, R.string.ok);
        ok.setOnClickListener(v -> finishTransform(true));
        ImageButton no = iconButton(R.drawable.ic_close, R.string.cancel);
        no.setOnClickListener(v -> finishTransform(false));
        side.addView(ok, new LinearLayout.LayoutParams(dp(52), dp(52)));
        View gap = new View(act);
        side.addView(gap, new LinearLayout.LayoutParams(1, 0, 1f));
        side.addView(no, new LinearLayout.LayoutParams(dp(52), dp(52)));
        tfPanel.addView(side, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lp.setMargins(dp(6), 0, dp(6), dp(6));
        root.addView(tfPanel, lp);
        fillTransformContent();
    }

    private void tfSlider(int title, int from, int to, java.util.function.Supplier<Integer> get,
            java.util.function.Consumer<Integer> set, java.util.function.Function<Integer, String> fmt) {
        tfContent.addView(label(act.getString(title), 14, fg));
        final TextView value = label("", 13, fgDim);
        final Slider sl = slider(from, to, Math.max(from, Math.min(to, get.get())));
        sl.addOnChangeListener((s, v, user) -> {
            if (user && !tfSyncing) {
                set.accept(Math.round(v));
            }
            value.setText(fmt.apply(Math.round(v)));
        });
        value.setText(fmt.apply(get.get()));
        tfSync.add(() -> {
            int cur = get.get();
            sl.setValue(Math.max(from, Math.min(to, cur)));
            value.setText(fmt.apply(cur));
        });
        tfContent.addView(sliderRowSimple(value, sl));
    }

    private void fillTransformContent() {
        tfContent.removeAllViews();
        tfSync.clear();
        if (tf.mode == TransformTool.MODE_SCALE) {
            int dw = tf.getDocWidth();
            int dh = tf.getDocHeight();
            tfSlider(R.string.transform_tx, -dw, dw, () -> Math.round(tf.tx),
                    v -> tf.setTranslation(v, tf.ty), v -> v + " px");
            tfSlider(R.string.transform_ty, -dh, dh, () -> Math.round(tf.ty),
                    v -> tf.setTranslation(tf.tx, v), v -> v + " px");
            tfSlider(R.string.transform_zoom, 1, 500, () -> Math.round(tf.zoom * 100f),
                    v -> tf.setZoom(v / 100f), v -> v + "%");
            tfSlider(R.string.transform_angle, -1800, 1800, () -> Math.round(tf.angle * 10f),
                    v -> tf.setAngle(v / 10f), v -> (v / 10f) + "\u00B0");
            tfContent.addView(switchRow(R.string.transform_repeat, tf.repeat, on -> {
                tf.setRepeat(on);
                fillTransformContent();
            }));
            if (tf.repeat) {
                tfSlider(R.string.transform_repeat_x, 5, 400, () -> tf.repX,
                        v -> tf.setRepeatSpacing(v, tf.repY), v -> (v / 100f) + "");
                tfSlider(R.string.transform_repeat_y, 5, 400, () -> tf.repY,
                        v -> tf.setRepeatSpacing(tf.repX, v), v -> (v / 100f) + "");
                tfSlider(R.string.transform_phase_x, 0, 100, () -> tf.phX,
                        v -> tf.setPhase(v, tf.phY), v -> v + "%");
                tfSlider(R.string.transform_phase_y, 0, 100, () -> tf.phY,
                        v -> tf.setPhase(tf.phX, v), v -> v + "%");
            }
        } else if (tf.mode == TransformTool.MODE_PERSPECTIVE) {
            tfContent.addView(switchRow(R.string.transform_perspective_on, tf.perspective,
                    on -> tf.setPerspective(on)));
            tfContent.addView(switchRow(R.string.transform_repeat, tf.perspectiveRepeat,
                    on -> tf.setPerspectiveRepeat(on)));
            TextView hint = label(act.getString(R.string.transform_corners_hint), 13, fgDim);
            hint.setPadding(dp(4), dp(8), 0, dp(8));
            tfContent.addView(hint);
        } else if (tf.mode == TransformTool.MODE_CUSTOM) {
            TextView hint = label(act.getString(R.string.transform_custom_hint), 13, fgDim);
            hint.setPadding(dp(4), dp(8), 0, dp(8));
            tfContent.addView(hint);
            tfSlider(R.string.transform_stiffness, 10, 100, () -> tf.stiffness,
                    v -> tf.setStiffness(v), v -> v + "%");
            LinearLayout row = new LinearLayout(act);
            TextView undo = label(act.getString(R.string.transform_pin_undo), 14, accent);
            undo.setPadding(dp(8), dp(10), dp(8), dp(10));
            undo.setOnClickListener(v -> tf.removeLastPin());
            TextView clear = label(act.getString(R.string.transform_pin_clear), 14, accent);
            clear.setPadding(dp(8), dp(10), dp(8), dp(10));
            clear.setOnClickListener(v -> tf.clearPins());
            row.addView(undo);
            row.addView(clear);
            tfContent.addView(row);
        } else {
            tfSlider(R.string.transform_div_x, 1, 10, () -> tf.divX,
                    v -> tf.setDivisions(v, tf.divY), v -> String.valueOf(v));
            tfSlider(R.string.transform_div_y, 1, 10, () -> tf.divY,
                    v -> tf.setDivisions(tf.divX, v), v -> String.valueOf(v));
            tfSlider(R.string.transform_smooth, 0, 100, () -> tf.smoothness,
                    v -> tf.setSmoothness(v), v -> v + "%");
        }
        final int[] interpNames = {R.string.interp_nearest, R.string.interp_average,
            R.string.interp_bilinear, R.string.interp_bicubic, R.string.interp_lanczos2,
            R.string.interp_lanczos3};
        final TextView interp = label(act.getString(interpNames[tf.interp]), 14,
                classic ? Color.BLACK : fg);
        interp.setBackground(round(classic ? 0xFFFFFFFF : 0x22808080, 8));
        interp.setPadding(dp(14), dp(10), dp(14), dp(10));
        interp.setOnClickListener(v -> {
            PopupMenu m = new PopupMenu(act, v);
            for (int i = 0; i < interpNames.length; i++) {
                m.getMenu().add(0, i, i, interpNames[i]);
            }
            m.setOnMenuItemClickListener(it -> {
                tf.setInterpolation(it.getItemId());
                interp.setText(interpNames[it.getItemId()]);
                return true;
            });
            m.show();
        });
        TextView it = label(act.getString(R.string.transform_interp), 14, fg);
        it.setPadding(0, dp(10), 0, dp(4));
        tfContent.addView(it);
        tfContent.addView(interp);
        LinearLayout frame = switchRow(R.string.transform_frame, tf.showFrame, on -> tf.setShowFrame(on));
        frame.setPadding(0, dp(10), 0, 0);
        tfContent.addView(frame);
    }

    private void finishTransform(boolean apply) {
        if (tf == null) {
            return;
        }
        Document doc = canvas.getDocument();
        if (apply) {
            int[] result = tf.renderPixels();
            canvas.getEditor().commitTransform(tfLayer, tfOriginal, result);
        } else if (tfNewLayer) {
            canvas.getEditor().undo(); // takes the empty layer away again
        } else {
            doc.getLayer(tfLayer).pasteRect(new nekopaint.core.Rect(0, 0, doc.getWidth(),
                    doc.getHeight()), tfOriginal);
        }
        FrameLayout root = act.findViewById(R.id.root);
        if (root != null && tfPanel != null) {
            root.removeView(tfPanel);
        }
        tfPanel = null;
        tfContent = null;
        tf = null;
        tfOriginal = null;
        tfSync.clear();
        canvas.setTransform(null);
        canvas.setActiveLayer(Math.max(0, Math.min(canvas.getActiveLayer(), doc.getLayerCount() - 1)));
        canvas.layerStructureChanged();
        canvas.layerPixelsEdited();
        host.onLayersEdited();
        refreshLayers();
    }

    // ------------------------------------------------------------------ tools

    private void showTools() {
        GridLayout grid = new GridLayout(act);
        final int cols = Math.max(2, Math.min(4, Opts.num(act, Opts.TOOL_COLS)));
        final boolean labels = Opts.flag(act, Opts.TOOL_LABELS);
        grid.setColumnCount(cols);
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
            if (labels) {
                cell.addView(name);
            }
            cell.setContentDescription(act.getString((Integer) it[1]));
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
        show(box, toolButton, panelWidth(88 * cols));
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
        int bar = classic ? 0xFF000000 : 0x22808080;

        LinearLayout left = sideBar(bar);
        left.addView(actionButton(R.drawable.ic_add, R.string.add_layer, v -> addLayer()), sideLp());
        left.addView(actionButton(R.drawable.ic_duplicate, R.string.layer_duplicate,
                v -> duplicateLayer()), sideLp());
        left.addView(actionButton(R.drawable.ic_camera, R.string.import_picture,
                v -> act.pickImageForLayer()), sideLp());
        left.addView(actionButton(R.drawable.ic_tool_lasso, R.string.selection_layer,
                v -> openSelectionLayer()), sideLp());
        left.addView(actionButton(R.drawable.ic_flip_h, R.string.layer_flip_h, v -> flipActive(true)), sideLp());
        left.addView(actionButton(R.drawable.ic_flip_v, R.string.layer_flip_v, v -> flipActive(false)), sideLp());

        LinearLayout right = sideBar(bar);
        right.addView(actionButton(R.drawable.ic_tool_transform, R.string.tool_transform,
                v -> startLayerTransform()), sideLp());
        right.addView(actionButton(R.drawable.ic_up, R.string.move_up, v -> moveActive(1)), sideLp());
        right.addView(actionButton(R.drawable.ic_down, R.string.move_down, v -> moveActive(-1)), sideLp());
        right.addView(actionButton(R.drawable.ic_merge, R.string.layer_merge, v -> mergeLayer()), sideLp());
        right.addView(actionButton(R.drawable.ic_delete, R.string.delete_layer, v -> deleteLayer()), sideLp());
        right.addView(actionButton(R.drawable.ic_more, R.string.layer_more, v -> showLayerMenu(v)), sideLp());

        LinearLayout centre = new LinearLayout(act);
        centre.setOrientation(LinearLayout.VERTICAL);
        scroll.setBackground(round(classic ? 0xFFF2F2F2 : 0x11808080, 4));
        centre.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        paperRow = new LinearLayout(act);
        paperRow.setGravity(Gravity.CENTER);
        paperRow.setPadding(0, dp(8), 0, dp(4));
        fillPaperRow();
        centre.addView(paperRow);

        LinearLayout mid = new LinearLayout(act);
        mid.addView(left, new LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        cl.leftMargin = dp(4);
        cl.rightMargin = dp(4);
        mid.addView(centre, cl);
        mid.addView(right, new LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.MATCH_PARENT));
        box.addView(mid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));

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
        LinearLayout modeRow = new LinearLayout(act);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        modeRow.addView(blendButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        clipButton = iconButton(R.drawable.ic_clip, R.string.layer_clip);
        clipButton.setOnClickListener(v -> {
            int at = canvas.getActiveLayer();
            if (at < 1) {
                Toast.makeText(act, R.string.layer_clip_bottom, Toast.LENGTH_SHORT).show();
                return;
            }
            Layer l = canvas.getDocument().getLayer(at);
            canvas.getEditor().setLayerClipped(at, !l.isClipped());
            canvas.layersChanged();
            host.onLayersEdited();
            rebuildLayerRows();
        });
        alphaButton = label("\u03B1", 20, fg);
        alphaButton.setTypeface(Typeface.DEFAULT_BOLD);
        alphaButton.setGravity(Gravity.CENTER);
        alphaButton.setContentDescription(act.getString(R.string.layer_alpha_lock));
        alphaButton.setOnClickListener(v -> {
            Layer l = canvas.getDocument().getLayer(canvas.getActiveLayer());
            canvas.getEditor().setLayerAlphaLock(canvas.getActiveLayer(), !l.isAlphaLock());
            canvas.layersChanged();
            rebuildLayerRows();
        });
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(44), dp(44));
        tp.leftMargin = dp(6);
        modeRow.addView(clipButton, tp);
        modeRow.addView(alphaButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        box.addView(modeRow, bl);
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
        if (canvas.getDocument().getLayer(at).isSelection()
                || canvas.getDocument().getLayer(at - 1).isSelection()) {
            Toast.makeText(act, R.string.selection_no_merge, Toast.LENGTH_SHORT).show();
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
        m.getMenu().add(0, 8, 7, R.string.layer_fill);
        m.getMenu().add(0, 9, 8, R.string.layer_solo);
        m.getMenu().add(0, 10, 9, R.string.layer_show_all);
        m.getMenu().add(0, 11, 10, R.string.layer_save_png);
        if (layer.isSelection()) {
            m.getMenu().add(0, 6, 5, R.string.selection_all);
            m.getMenu().add(0, 7, 6, R.string.selection_invert);
        }
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1:
                    renameLayer(layer);
                    return true;
                case 9:
                case 10:
                    for (int i = 0; i < canvas.getDocument().getLayerCount(); i++) {
                        Layer o = canvas.getDocument().getLayer(i);
                        if (!o.isSelection()) {
                            canvas.getEditor().setLayerVisible(i, it.getItemId() == 10 || i == index);
                        }
                    }
                    canvas.layersChanged();
                    layersEdited();
                    return true;
                case 11:
                    act.saveLayerAsPng(index, layer.getName());
                    return true;
                case 2:
                    canvas.getEditor().setLayerLocked(index, !layer.isLocked());
                    canvas.layersChanged();
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
            } else if (it.getItemId() == 8) {
                canvas.getEditor().fillLayer(index, color);
            } else if (it.getItemId() == 6) {
                canvas.getEditor().selectAll(index);
            } else if (it.getItemId() == 7) {
                canvas.getEditor().invertSelection(index);
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
                        canvas.getEditor().renameLayer(canvas.getDocument().indexOf(layer),
                                n.length() > 40 ? n.substring(0, 40) : n);
                        canvas.layersChanged();
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

    private LinearLayout sideBar(int color) {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(color, 6));
        return l;
    }

    private LinearLayout.LayoutParams sideLp() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
    }

    private void flipActive(boolean horizontal) {
        if (!canvas.isIdle()) {
            return;
        }
        canvas.getEditor().flipLayer(canvas.getActiveLayer(), horizontal);
        canvas.layersChanged();
        layersEdited();
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
        canvas.setActiveLayer(Opts.flag(act, Opts.ADD_CURRENT) ? at : Math.max(0, at - 1));
        canvas.layerStructureChanged();
        layersEdited();
    }

    private void deleteLayer() {
        Document doc = canvas.getDocument();
        int real = 0;
        for (int i = 0; i < doc.getLayerCount(); i++) {
            if (!doc.getLayer(i).isSelection()) {
                real++;
            }
        }
        int index = canvas.getActiveLayer();
        if (doc.getLayerCount() <= 1 || !canvas.isIdle()
                || (real <= 1 && !doc.getLayer(index).isSelection())) {
            return;
        }
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

    /** Makes the selection layer (once) and starts editing it: brush selects, eraser deselects. */
    private void openSelectionLayer() {
        Document doc = canvas.getDocument();
        if (!canvas.isIdle()) {
            return;
        }
        if (doc.getSelectionLayer() == null) {
            if (doc.getLayerCount() >= 30) {
                return;
            }
            canvas.getEditor().addSelectionLayer();
            canvas.layerStructureChanged();
            Toast.makeText(act, R.string.selection_hint, Toast.LENGTH_LONG).show();
        }
        for (int i = 0; i < doc.getLayerCount(); i++) {
            if (doc.getLayer(i).isSelection()) {
                canvas.setActiveLayer(i);
            }
        }
        layersEdited();
    }

    void layersChangedFromOutside() {
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
        final int rowThumb = Math.max(40, Math.min(90, Opts.num(act, Opts.ROW_HEIGHT))) - 8;
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
            t.setImageBitmap(thumb(layer, dp(rowThumb - 4)));
            t.setPadding(dp(2), dp(2), dp(2), dp(2));
            row.addView(t, new LinearLayout.LayoutParams(dp(rowThumb), dp(rowThumb)));

            LinearLayout text = new LinearLayout(act);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(dp(10), 0, dp(6), 0);
            TextView name = label(layer.isSelection() ? act.getString(R.string.selection_layer)
                    : layer.getName(), 14, layer.isSelection() ? accent : fg);
            name.setSingleLine(true);
            text.addView(name);
            String mode = blendName(layer.getBlendMode());
            text.addView(label((layer.isClipped() ? "\u21B3 " : "")
                    + (layer.isAlphaLock() ? "\u03B1 " : "")
                    + Math.round(layer.getOpacity() * 100f / 255f) + "%  " + mode, 12, fgDim));
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (layer.isLocked()) {
                ImageView lock = new ImageView(act);
                lock.setImageResource(R.drawable.ic_lock);
                lock.setImageTintList(ColorStateList.valueOf(fgDim));
                row.addView(lock, new LinearLayout.LayoutParams(dp(20), dp(20)));
            }
            final TextView handle = label("\u2261", 22, fgDim);
            handle.setGravity(Gravity.CENTER);
            handle.setOnTouchListener(new View.OnTouchListener() {
                float downY;
                @Override
                public boolean onTouch(View hv, MotionEvent e) {
                    View r = (View) hv.getParent();
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downY = e.getRawY();
                            r.setElevation(dp(6));
                            layerRows.requestDisallowInterceptTouchEvent(true);
                            hv.getParent().requestDisallowInterceptTouchEvent(true);
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            r.setTranslationY(e.getRawY() - downY);
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            float rh = Math.max(1, r.getHeight());
                            int steps = Math.round((e.getRawY() - downY) / rh);
                            r.setTranslationY(0);
                            r.setElevation(0);
                            int to = Math.max(0, Math.min(doc.getLayerCount() - 1, index - steps));
                            if (e.getActionMasked() == MotionEvent.ACTION_UP && to != index
                                    && canvas.isIdle()) {
                                canvas.getEditor().moveLayer(index, to);
                                canvas.setActiveLayer(to);
                                canvas.layerStructureChanged();
                                layersEdited();
                            }
                            return true;
                        default:
                            return true;
                    }
                }
            });
            ImageButton eye = iconButton(layer.isVisible() ? R.drawable.ic_eye : R.drawable.ic_eye_off,
                    R.string.visibility);
            eye.setOnClickListener(v -> {
                canvas.getEditor().setLayerVisible(index, !layer.isVisible());
                canvas.layersChanged();
                host.onLayersEdited();
                rebuildLayerRows();
            });
            row.addView(eye, new LinearLayout.LayoutParams(dp(44), dp(44)));
            row.addView(handle, new LinearLayout.LayoutParams(dp(34), dp(44)));
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
        clipButton.setBackground(active.isClipped() ? round(rowSelected, 10) : null);
        alphaButton.setBackground(active.isAlphaLock() ? round(rowSelected, 10) : null);
        syncing = false;
    }
}
