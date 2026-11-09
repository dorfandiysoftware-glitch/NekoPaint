// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/** The start screen: settings at the top right, the logo, and the gallery button. */
public class HomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Prefs.isSetupDone(this)) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_home);
        findViewById(R.id.home_settings).setOnClickListener(v -> {
            Intent i = new Intent(this, SetupActivity.class);
            i.putExtra(SetupActivity.EXTRA_SETTINGS, true);
            startActivity(i);
        });
        findViewById(R.id.home_gallery).setOnClickListener(
                v -> startActivity(new Intent(this, GalleryActivity.class)));
    }
}
