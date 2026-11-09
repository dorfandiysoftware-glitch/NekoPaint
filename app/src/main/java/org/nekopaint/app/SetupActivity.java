// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ViewFlipper;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;

/** First-run setup, one question per page: welcome, theme, interface, canvas size, done. */
public class SetupActivity extends AppCompatActivity {

    private ViewFlipper flipper;
    private MaterialButton back;
    private MaterialButton next;
    private LinearLayout dots;
    private MaterialButton sizeButton;
    private MaterialCardView uiM3;
    private int page;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);
        flipper = findViewById(R.id.wizard_flipper);
        back = findViewById(R.id.wizard_back);
        next = findViewById(R.id.wizard_next);
        dots = findViewById(R.id.wizard_dots);
        flipper.setInAnimation(this, android.R.anim.fade_in);
        flipper.setOutAnimation(this, android.R.anim.fade_out);
        if (savedInstanceState != null) {
            page = savedInstanceState.getInt("page", 0);
            flipper.setDisplayedChild(page);
        }

        // Theme
        MaterialButtonToggleGroup theme = findViewById(R.id.setup_theme);
        int current = Prefs.theme(this);
        theme.check(current == 1 ? R.id.theme_light
                : current == 2 ? R.id.theme_dark : R.id.theme_system);
        theme.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) {
                return;
            }
            int mode = id == R.id.theme_light ? 1 : id == R.id.theme_dark ? 2 : 0;
            Prefs.setTheme(this, mode);
            NekoApp.applyTheme(mode);
        });

        // Interface
        uiM3 = findViewById(R.id.ui_m3);
        uiM3.setChecked(Prefs.md3Colors(this));
        uiM3.setOnClickListener(v -> {
            boolean on = !Prefs.md3Colors(this);
            Prefs.setMd3Colors(this, on);
            uiM3.setChecked(on);
        });

        // Canvas size
        sizeButton = findViewById(R.id.setup_size);
        showSize();
        sizeButton.setOnClickListener(v -> CanvasSizeDialog.show(this, Prefs.width(this),
                Prefs.height(this), (w, h) -> {
                    Prefs.setSize(this, w, h);
                    showSize();
                }));

        back.setOnClickListener(v -> go(page - 1));
        next.setOnClickListener(v -> {
            if (page == flipper.getChildCount() - 1) {
                Prefs.setSetupDone(this);
                startActivity(new Intent(this, HomeActivity.class));
                finish();
            } else {
                go(page + 1);
            }
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (page > 0) {
                    go(page - 1);
                } else {
                    finish();
                }
            }
        });
        buildDots();
        updateNav();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", page);
    }

    private void go(int target) {
        if (target < 0 || target >= flipper.getChildCount()) {
            return;
        }
        page = target;
        flipper.setDisplayedChild(page);
        updateNav();
    }

    private void updateNav() {
        back.setVisibility(page == 0 ? View.INVISIBLE : View.VISIBLE);
        next.setText(page == flipper.getChildCount() - 1 ? R.string.setup_start : R.string.next);
        for (int i = 0; i < dots.getChildCount(); i++) {
            dots.getChildAt(i).setAlpha(i == page ? 1f : 0.3f);
        }
    }

    private void buildDots() {
        int size = (int) (8 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < flipper.getChildCount(); i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(size / 2, 0, size / 2, 0);
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(R.drawable.wizard_dot);
            dots.addView(dot);
        }
    }

    private void showSize() {
        sizeButton.setText(Prefs.width(this) + " × " + Prefs.height(this));
    }
}
