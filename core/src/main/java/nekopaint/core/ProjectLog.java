// SPDX-License-Identifier: GPL-3.0-or-later
package nekopaint.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A recording of how a drawing was made: strokes and layer changes in order, starting from a
 * blank canvas with one layer. It powers the time-lapse replay.
 *
 * <p>Undone strokes are removed from the log (and come back on redo), so replaying the log
 * gives the same picture as the drawing itself. When the log grows past {@link #MAX_POINTS}
 * recording stops and {@link #isTruncated()} becomes true.
 */
public final class ProjectLog {

    public static final int MAX_POINTS = 1500000;

    static final int STROKE = 1;
    static final int ADD_LAYER = 2;
    static final int REMOVE_LAYER = 3;
    static final int MOVE_LAYER = 4;
    static final int VISIBLE = 5;
    static final int OPACITY = 6;
    static final int BLEND = 7;

    private static final int MAGIC = 0x4E504C47; // "NPLG"
    private static final int VERSION = 1;
    private static final int MAX_EVENTS = 2000000;

    /** One step of the recording. */
    static final class Event {
        final int type;
        final int a;
        final int b;
        final String name;
        final Brush brush;
        /** x, y, pressure for each point. */
        final float[] points;

        Event(int type, int a, int b, String name, Brush brush, float[] points) {
            this.type = type;
            this.a = a;
            this.b = b;
            this.name = name;
            this.brush = brush;
            this.points = points;
        }
    }

    private final int width;
    private final int height;
    private final List<Event> events = new ArrayList<Event>();
    private int pointCount;
    private boolean truncated;

    public ProjectLog(int width, int height) {
        Layer.checkSize(width, height);
        this.width = width;
        this.height = height;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public boolean isEmpty() {
        return events.isEmpty();
    }

    /** @return how many stroke points the log holds in total */
    public int getPointCount() {
        return pointCount;
    }

    List<Event> events() {
        return events;
    }

    /** @return the recorded event, or null if the log is full */
    Event addStroke(int layerIndex, Brush brush, float[] points, int count) {
        if (pointCount + count > MAX_POINTS) {
            truncated = true;
            return null;
        }
        float[] copy = new float[count * 3];
        System.arraycopy(points, 0, copy, 0, count * 3);
        Event e = new Event(STROKE, layerIndex, 0, null, brush, copy);
        events.add(e);
        pointCount += count;
        return e;
    }

    void addOp(int type, int a, int b, String name) {
        if (events.size() < MAX_EVENTS) {
            events.add(new Event(type, a, b, name, null, null));
        } else {
            truncated = true;
        }
    }

    /** Takes an undone stroke out of the log. */
    void remove(Event e) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i) == e) {
                events.remove(i);
                pointCount -= e.points.length / 3;
                return;
            }
        }
    }

    /** Puts a redone stroke back (at the end). */
    void restore(Event e) {
        events.add(e);
        pointCount += e.points.length / 3;
    }

    // ------------------------------------------------------------------ files

    public void write(DataOutputStream out) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(width);
        out.writeInt(height);
        out.writeBoolean(truncated);
        out.writeInt(events.size());
        for (Event e : events) {
            out.writeByte(e.type);
            switch (e.type) {
                case STROKE:
                    out.writeInt(e.a);
                    out.writeInt(e.brush.getRgb());
                    out.writeFloat(e.brush.getRadius());
                    out.writeFloat(e.brush.getHardness());
                    out.writeFloat(e.brush.getFlow());
                    out.writeFloat(e.brush.getSpacing());
                    out.writeBoolean(e.brush.isEraser());
                    out.writeInt(e.points.length / 3);
                    for (float f : e.points) {
                        out.writeFloat(f);
                    }
                    break;
                case ADD_LAYER:
                    out.writeInt(e.a);
                    out.writeUTF(e.name);
                    break;
                default:
                    out.writeInt(e.a);
                    out.writeInt(e.b);
                    break;
            }
        }
    }

    /** @throws IOException if the data is not a valid log */
    public static ProjectLog read(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC || in.readInt() != VERSION) {
            throw new IOException("Not a NekoPaint log");
        }
        int w = in.readInt();
        int h = in.readInt();
        ProjectLog log;
        try {
            log = new ProjectLog(w, h);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Bad log size");
        }
        log.truncated = in.readBoolean();
        int count = in.readInt();
        if (count < 0 || count > MAX_EVENTS) {
            throw new IOException("Bad log");
        }
        for (int i = 0; i < count; i++) {
            int type = in.readUnsignedByte();
            switch (type) {
                case STROKE: {
                    int layer = in.readInt();
                    int rgb = in.readInt();
                    float radius = in.readFloat();
                    float hardness = in.readFloat();
                    float flow = in.readFloat();
                    float spacing = in.readFloat();
                    boolean eraser = in.readBoolean();
                    int n = in.readInt();
                    if (n < 0 || log.pointCount + n > MAX_POINTS) {
                        throw new IOException("Bad log");
                    }
                    float[] pts = new float[n * 3];
                    for (int k = 0; k < pts.length; k++) {
                        pts[k] = in.readFloat();
                    }
                    Brush brush;
                    try {
                        brush = new Brush(rgb, radius, hardness, flow, spacing, eraser);
                    } catch (IllegalArgumentException ex) {
                        throw new IOException("Bad brush in log");
                    }
                    log.events.add(new Event(STROKE, layer, 0, null, brush, pts));
                    log.pointCount += n;
                    break;
                }
                case ADD_LAYER:
                    log.events.add(new Event(type, in.readInt(), 0, in.readUTF(), null, null));
                    break;
                case REMOVE_LAYER:
                case MOVE_LAYER:
                case VISIBLE:
                case OPACITY:
                case BLEND:
                    log.events.add(new Event(type, in.readInt(), in.readInt(), null, null,
                            null));
                    break;
                default:
                    throw new IOException("Unknown log entry");
            }
        }
        return log;
    }

    // ------------------------------------------------------------------ replay

    /** Replays a log step by step on a fresh document. */
    public static final class Replay {
        private final ProjectLog log;
        private final Document document;
        private int next;
        private StrokeRasterizer rasterizer;
        private float[] points;
        private int pointIndex;
        private Rect changed = Rect.EMPTY;

        public Replay(ProjectLog log) {
            this.log = log;
            this.document = new Document(log.width, log.height);
            document.addLayer("Layer 1");
        }

        public Document getDocument() {
            return document;
        }

        public boolean isDone() {
            return rasterizer == null && next >= log.events.size();
        }

        /**
         * Replays up to the given number of stroke points (layer changes are free).
         *
         * @return the area of the canvas that changed since the previous call, or empty
         */
        public Rect step(int pointBudget) {
            changed = Rect.EMPTY;
            int budget = Math.max(1, pointBudget);
            while (budget > 0) {
                if (rasterizer != null) {
                    int total = points.length / 3;
                    while (budget > 0 && pointIndex < total) {
                        int i = pointIndex * 3;
                        rasterizer.addPoint(points[i], points[i + 1], points[i + 2]);
                        pointIndex++;
                        budget--;
                    }
                    changed = changed.union(rasterizer.takeChanged());
                    if (pointIndex >= total) {
                        rasterizer = null;
                    }
                    continue;
                }
                if (next >= log.events.size()) {
                    break;
                }
                apply(log.events.get(next++));
            }
            return changed;
        }

        private void apply(Event e) {
            int count = document.getLayerCount();
            switch (e.type) {
                case STROKE:
                    if (e.a >= 0 && e.a < count) {
                        rasterizer = new StrokeRasterizer(document.getLayer(e.a), e.brush);
                        points = e.points;
                        pointIndex = 0;
                    }
                    break;
                case ADD_LAYER: {
                    document.addLayer(e.name);
                    int at = Math.max(0, Math.min(e.a, document.getLayerCount() - 1));
                    document.moveLayer(document.getLayerCount() - 1, at);
                    changed = new Rect(0, 0, document.getWidth(), document.getHeight());
                    break;
                }
                case REMOVE_LAYER:
                    if (e.a >= 0 && e.a < count && count > 1) {
                        document.removeLayer(e.a);
                        changed = new Rect(0, 0, document.getWidth(), document.getHeight());
                    }
                    break;
                case MOVE_LAYER:
                    if (e.a >= 0 && e.a < count && e.b >= 0 && e.b < count) {
                        document.moveLayer(e.a, e.b);
                        changed = new Rect(0, 0, document.getWidth(), document.getHeight());
                    }
                    break;
                case VISIBLE:
                    if (e.a >= 0 && e.a < count) {
                        document.getLayer(e.a).setVisible(e.b != 0);
                        changed = new Rect(0, 0, document.getWidth(), document.getHeight());
                    }
                    break;
                case OPACITY:
                    if (e.a >= 0 && e.a < count) {
                        document.getLayer(e.a).setOpacity(Math.max(0, Math.min(255, e.b)));
                        changed = new Rect(0, 0, document.getWidth(), document.getHeight());
                    }
                    break;
                case BLEND:
                    if (e.a >= 0 && e.a < count && e.b >= 0
                            && e.b < BlendMode.values().length) {
                        document.getLayer(e.a).setBlendMode(BlendMode.values()[e.b]);
                        changed = new Rect(0, 0, document.getWidth(), document.getHeight());
                    }
                    break;
                default:
                    break;
            }
        }
    }
}
