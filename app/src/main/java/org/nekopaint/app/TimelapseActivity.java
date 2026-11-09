// SPDX-License-Identifier: GPL-3.0-or-later
package org.nekopaint.app;

import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.IOException;
import java.io.InputStream;

import nekopaint.core.OpenRaster;
import nekopaint.core.ProjectLog;
import nekopaint.core.Rect;

/** Plays a fast time-lapse of how a drawing was made. */
public class TimelapseActivity extends AppCompatActivity {

    private static final int SECONDS = 12;
    private static final int FPS = 60;

    private CanvasView canvas;
    private LinearProgressIndicator progress;
    private ProjectLog.Replay replay;
    private int pointsPerFrame;
    private int totalPoints;
    private int shown;
    private boolean running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_timelapse);
        canvas = findViewById(R.id.timelapse_canvas);
        progress = findViewById(R.id.timelapse_progress);
        canvas.setInteractive(false);
        findViewById(R.id.timelapse_close).setOnClickListener(v -> finish());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final Uri uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }
        Toast.makeText(this, "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            ProjectLog log = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    log = OpenRaster.readProject(in).log;
                }
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                log = null;
            }
            final ProjectLog result = log;
            runOnUiThread(() -> start(result));
        }, "timelapse-load").start();
    }

    private void start(ProjectLog log) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (log == null || log.isEmpty()) {
            Toast.makeText(this, R.string.no_timelapse, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        replay = new ProjectLog.Replay(log);
        canvas.setDocument(replay.getDocument());
        totalPoints = Math.max(1, log.getPointCount());
        pointsPerFrame = Math.max(1, totalPoints / (SECONDS * FPS));
        running = true;
        canvas.postOnAnimation(this::frame);
    }

    private void frame() {
        if (!running || replay == null) {
            return;
        }
        Rect changed = replay.step(pointsPerFrame);
        shown += pointsPerFrame;
        if (!changed.isEmpty()) {
            if (changed.width() >= replay.getDocument().getWidth()
                    && changed.height() >= replay.getDocument().getHeight()) {
                canvas.layersChanged();
            } else {
                canvas.externalChanged(changed);
            }
        }
        progress.setProgress(Math.min(1000, (int) (1000L * shown / totalPoints)));
        if (replay.isDone()) {
            running = false;
            progress.setProgress(1000);
            canvas.layersChanged();
            return;
        }
        canvas.postOnAnimation(this::frame);
    }

    @Override
    protected void onDestroy() {
        running = false;
        super.onDestroy();
    }
}
