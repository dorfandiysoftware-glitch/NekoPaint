// SPDX-License-Identifier: GPL-3.0-or-later
using System;
using System.Collections.Generic;
using System.Linq;

namespace NekoPaint.Core
{
    /// <summary>
    /// Editing session: strokes and layer operations with undo/redo. Pixel edits are stored as 64x64 tile
    /// diffs; structural changes (layers added, moved, ...) are stored as undo/redo actions.
    /// Port of the Java Editor (the crash-recovery project log is not ported yet).
    /// </summary>
    public sealed class Editor
    {
        public const string KindLayer = "layer";
        public const string KindStroke = "stroke";
        public const string KindErase = "erase";
        public const string KindClear = "clear";
        public const string KindFlip = "flip";
        public const string KindTransform = "transform";

        private const int Tile = 64;
        private const long MaxHistoryBytes = 96L * 1024 * 1024;

        private readonly Document _document;
        private readonly int _maxHistory;
        private readonly LinkedList<Edit> _undo = new LinkedList<Edit>();
        private readonly LinkedList<Edit> _redo = new LinkedList<Edit>();
        private Stroke _active;

        public Document Document => _document;
        /// <summary>Area changed by the last undo/redo/edit, for repainting.</summary>
        public Rect LastChanged { get; private set; } = Rect.Empty;
        public string LastKind { get; private set; } = KindStroke;
        /// <summary>True when the last undo/redo was structural (layers added/moved/...).</summary>
        public bool WasStructural { get; private set; }

        public Editor(Document document, int maxHistory)
        {
            if (document == null || maxHistory < 1) throw new ArgumentException("A document and a history size of at least 1 are required");
            _document = document; _maxHistory = maxHistory;
        }

        public bool CanUndo => _undo.Count > 0;
        public bool CanRedo => _redo.Count > 0;

        // ---- history plumbing ----

        private void Trim()
        {
            while (_undo.Count > _maxHistory || (_undo.Count > 1 && HistoryBytes() > MaxHistoryBytes))
                _undo.RemoveFirst();
        }

        private long HistoryBytes() => _undo.Sum(e => e.Bytes());

        private void PushOp(Action undoOp, Action redoOp, object key)
        {
            var last = _undo.Last?.Value;
            if (key != null && last != null && key.Equals(last.Key) && _redo.Count == 0)
            {
                last.RedoOp = redoOp; // coalesce slider drags
                return;
            }
            var e = new Edit(0, 0, new int[0], new int[0][], new int[0][],
                new Rect(0, 0, _document.Width, _document.Height), KindLayer)
            { UndoOp = undoOp, RedoOp = redoOp, Key = key };
            _undo.AddLast(e);
            _redo.Clear();
            Trim();
        }

        private void RequireNoStroke()
        {
            if (_active != null) throw new InvalidOperationException("A stroke is in progress");
        }

        public bool Undo()
        {
            RequireNoStroke();
            if (_undo.Count == 0) return false;
            var edit = _undo.Last.Value; _undo.RemoveLast();
            WasStructural = edit.UndoOp != null;
            if (edit.UndoOp != null) edit.UndoOp();
            else edit.Apply(_document.GetLayer(edit.LayerIndex), edit.Before);
            LastChanged = edit.Bounds; LastKind = edit.Kind;
            _redo.AddLast(edit);
            return true;
        }

        public bool Redo()
        {
            RequireNoStroke();
            if (_redo.Count == 0) return false;
            var edit = _redo.Last.Value; _redo.RemoveLast();
            WasStructural = edit.RedoOp != null;
            if (edit.RedoOp != null) edit.RedoOp();
            else edit.Apply(_document.GetLayer(edit.LayerIndex), edit.After);
            LastChanged = edit.Bounds; LastKind = edit.Kind;
            _undo.AddLast(edit);
            return true;
        }

        public void ClearHistory()
        {
            RequireNoStroke();
            _undo.Clear(); _redo.Clear(); LastChanged = Rect.Empty;
        }

        // ---- layers ----

        public Layer AddLayer(string name, int at)
        {
            RequireNoStroke();
            var layer = _document.AddLayer(name);
            int target = Math.Max(0, Math.Min(at, _document.LayerCount - 1));
            _document.MoveLayer(_document.LayerCount - 1, target);
            PushOp(() => _document.DetachLayer(_document.IndexOf(layer)),
                   () => _document.InsertLayer(target, layer), null);
            return layer;
        }

        public void RemoveLayer(int index)
        {
            RequireNoStroke();
            var layer = _document.GetLayer(index);
            _document.RemoveLayer(index);
            PushOp(() => _document.InsertLayer(index, layer),
                   () => _document.DetachLayer(_document.IndexOf(layer)), null);
        }

        public void MoveLayer(int from, int to)
        {
            RequireNoStroke();
            _document.MoveLayer(from, to);
            PushOp(() => _document.MoveLayer(to, from), () => _document.MoveLayer(from, to), null);
        }

        public void SetLayerVisible(int index, bool visible)
        {
            var l = _document.GetLayer(index);
            bool old = l.Visible;
            if (old == visible) return;
            l.Visible = visible;
            PushOp(() => l.Visible = old, () => l.Visible = visible, null);
        }

        public void SetLayerOpacity(int index, int opacity)
        {
            var l = _document.GetLayer(index);
            int old = l.Opacity;
            if (old == opacity) return;
            l.Opacity = opacity;
            PushOp(() => l.Opacity = old, () => l.Opacity = opacity, (l, "opacity"));
        }

        public void SetLayerBlendMode(int index, BlendMode mode)
        {
            var l = _document.GetLayer(index);
            var old = l.BlendMode;
            if (old == mode) return;
            l.BlendMode = mode;
            PushOp(() => l.BlendMode = old, () => l.BlendMode = mode, null);
        }

        public void SetLayerClipped(int index, bool on)
        {
            var l = _document.GetLayer(index);
            bool old = l.Clipped;
            if (old == on) return;
            l.Clipped = on;
            PushOp(() => l.Clipped = old, () => l.Clipped = on, null);
        }

        public void SetLayerAlphaLock(int index, bool on)
        {
            var l = _document.GetLayer(index);
            bool old = l.AlphaLock;
            if (old == on) return;
            l.AlphaLock = on;
            PushOp(() => l.AlphaLock = old, () => l.AlphaLock = on, null);
        }

        public void SetLayerLocked(int index, bool on)
        {
            var l = _document.GetLayer(index);
            bool old = l.Locked;
            if (old == on) return;
            l.Locked = on;
            PushOp(() => l.Locked = old, () => l.Locked = on, null);
        }

        public void RenameLayer(int index, string name)
        {
            var l = _document.GetLayer(index);
            string old = l.Name;
            if (old == name) return;
            l.Name = name;
            PushOp(() => l.Name = old, () => l.Name = name, null);
        }

        public Layer AddSelectionLayer()
        {
            var existing = _document.GetSelectionLayer();
            if (existing != null) return existing;
            var layer = AddLayer("Selection", _document.LayerCount);
            layer.Selection = true;
            return layer;
        }

        public Layer DuplicateLayer(int index)
        {
            RequireNoStroke();
            var src = _document.GetLayer(index);
            var copy = AddLayer(src.Name + " copy", index + 1);
            copy.PasteRect(new Rect(0, 0, src.Width, src.Height), src.CopyPixels());
            copy.Opacity = src.Opacity; copy.BlendMode = src.BlendMode; copy.Visible = src.Visible;
            copy.Clipped = src.Clipped; copy.AlphaLock = src.AlphaLock;
            return copy;
        }

        public void MergeDown(int index)
        {
            RequireNoStroke();
            if (index < 1 || index >= _document.LayerCount) throw new ArgumentException("There is no layer below");
            var top = _document.GetLayer(index);
            var baseLayer = _document.GetLayer(index - 1);
            int[] before = baseLayer.CopyPixels();
            int[] b = (int[])before.Clone();
            if (top.Visible && top.Opacity > 0)
            {
                int[] t = top.CopyPixels();
                int opacity = top.Opacity;
                var mode = top.BlendMode;
                for (int i = 0; i < b.Length; i++)
                {
                    int s = t[i];
                    if (s == 0) continue;
                    if (top.Clipped)
                    {
                        int ma = PixelOps.Alpha(b[i]);
                        if (ma == 0) continue;
                        s = PixelOps.Scale(s, ma);
                    }
                    if (opacity != 255) s = PixelOps.Scale(s, opacity);
                    if (PixelOps.Alpha(s) == 0) continue;
                    b[i] = Blend.Apply(mode, s, b[i]);
                }
            }
            var all = new Rect(0, 0, baseLayer.Width, baseLayer.Height);
            baseLayer.PasteRect(all, b);
            _document.RemoveLayer(index);
            PushOp(() => { baseLayer.PasteRect(all, before); _document.InsertLayer(index, top); },
                   () => { baseLayer.PasteRect(all, b); _document.DetachLayer(_document.IndexOf(top)); }, null);
        }

        // ---- whole-layer pixel edits ----

        public void ClearLayer(int index)
        {
            var l = _document.GetLayer(index);
            ReplacePixels(index, new int[l.Width * l.Height], KindClear);
        }

        public void FillLayer(int index, int rgb)
        {
            var l = _document.GetLayer(index);
            var px = new int[l.Width * l.Height];
            Array.Fill(px, unchecked((int)0xFF000000) | (rgb & 0xFFFFFF));
            ReplacePixels(index, px, KindStroke);
        }

        public void FlipLayer(int index, bool horizontal)
        {
            var l = _document.GetLayer(index);
            int w = l.Width, h = l.Height;
            int[] src = l.CopyPixels();
            var dst = new int[src.Length];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                {
                    int sx = horizontal ? w - 1 - x : x;
                    int sy = horizontal ? y : h - 1 - y;
                    dst[y * w + x] = src[sy * w + sx];
                }
            ReplacePixels(index, dst, KindFlip);
        }

        /// <summary>Replaces a whole layer as one undo step; only changed tiles are stored.</summary>
        private void ReplacePixels(int index, int[] after, string kind)
        {
            RequireNoStroke();
            var layer = _document.GetLayer(index);
            int w = layer.Width, h = layer.Height;
            int across = (w + Tile - 1) / Tile, down = (h + Tile - 1) / Tile;
            var keys = new int[across * down];
            var before = new int[keys.Length][];
            var now = new int[keys.Length][];
            int count = 0;
            Rect bounds = Rect.Empty;
            for (int ty = 0; ty < down; ty++)
                for (int tx = 0; tx < across; tx++)
                {
                    int key = ty * across + tx;
                    Rect r = TileRect(key, across, w, h);
                    int[] old = layer.CopyRect(r);
                    int rw = r.Width;
                    var fresh = new int[old.Length];
                    for (int y = 0; y < r.Height; y++)
                        Array.Copy(after, (r.Top + y) * w + r.Left, fresh, y * rw, rw);
                    if (old.AsSpan().SequenceEqual(fresh)) continue;
                    keys[count] = key; before[count] = old; now[count] = fresh; count++;
                    bounds = bounds.Union(r);
                }
            if (count == 0) return;
            var edit = new Edit(index, across, keys.Take(count).ToArray(), before.Take(count).ToArray(),
                now.Take(count).ToArray(), bounds, kind);
            edit.Apply(layer, edit.After);
            LastChanged = bounds;
            _undo.AddLast(edit);
            _redo.Clear();
            Trim();
        }

        // ---- strokes ----

        public Stroke BeginStroke(int layerIndex, Brush brush)
        {
            if (_active != null) throw new InvalidOperationException("A stroke is already in progress");
            if (layerIndex < 0 || layerIndex >= _document.LayerCount || brush == null)
                throw new ArgumentException("Invalid layer or brush");
            _active = new Stroke(this, layerIndex, _document.GetLayer(layerIndex), brush);
            return _active;
        }

        private static Rect TileRect(int key, int across, int width, int height)
        {
            int tx = key % across, ty = key / across;
            return new Rect(tx * Tile, ty * Tile, Math.Min(width, (tx + 1) * Tile), Math.Min(height, (ty + 1) * Tile));
        }

        /// <summary>One stroke in progress. Changed tiles are saved on first touch, so cancel and undo are exact.</summary>
        public sealed class Stroke
        {
            private const float MaxCoord = 100000f;
            private readonly Editor _ed;
            private readonly int _layerIndex;
            private readonly Layer _layer;
            private readonly int _tilesAcross;
            private readonly Dictionary<int, int[]> _saved = new Dictionary<int, int[]>();
            private readonly StrokeRasterizer _raster;
            private readonly float _radius;
            private readonly Brush _brush;
            private readonly int[] _mask;
            private bool _hasLast, _finished;
            private float _lastX, _lastY;

            internal Stroke(Editor ed, int layerIndex, Layer layer, Brush brush)
            {
                _ed = ed; _layerIndex = layerIndex; _layer = layer; _brush = brush;
                _tilesAcross = (layer.Width + Tile - 1) / Tile;
                _radius = brush.Radius;
                _raster = new StrokeRasterizer(layer, brush);
                var sel = ed._document.GetSelectionLayer();
                if (sel != null && sel != layer && sel.Visible) _mask = sel.Pixels;
            }

            public void AddPoint(float x, float y, float pressure)
            {
                RequireActive();
                int lockL = 0, lockT = 0, lockR = 0, lockB = 0;
                bool lockArea = false;
                if (!float.IsNaN(x) && !float.IsNaN(y) && !float.IsInfinity(x) && !float.IsInfinity(y))
                {
                    x = Math.Max(-MaxCoord, Math.Min(MaxCoord, x));
                    y = Math.Max(-MaxCoord, Math.Min(MaxCoord, y));
                    float x0 = _hasLast ? Math.Min(_lastX, x) : x;
                    float x1 = _hasLast ? Math.Max(_lastX, x) : x;
                    float y0 = _hasLast ? Math.Min(_lastY, y) : y;
                    float y1 = _hasLast ? Math.Max(_lastY, y) : y;
                    lockL = (int)Math.Floor(x0 - _radius) - 1;
                    lockT = (int)Math.Floor(y0 - _radius) - 1;
                    lockR = (int)Math.Ceiling(x1 + _radius) + 2;
                    lockB = (int)Math.Ceiling(y1 + _radius) + 2;
                    SaveTiles(lockL, lockT, lockR, lockB);
                    lockArea = true;
                    _hasLast = true; _lastX = x; _lastY = y;
                }
                _raster.AddPoint(x, y, pressure);
                if (lockArea && _mask != null) ApplyMask(lockL, lockT, lockR, lockB);
                if (lockArea && _layer.AlphaLock) LockAlpha(lockL, lockT, lockR, lockB);
            }

            public Rect TakeChanged() => _raster.TakeChanged();
            public Rect GetDirty() => _raster.GetDirty();

            public void End()
            {
                RequireActive();
                _finished = true; _ed._active = null;
                if (_raster.GetDirty().IsEmpty) return;
                var edit = BuildEdit();
                if (edit == null) return; // nothing really changed
                _ed._undo.AddLast(edit);
                _ed._redo.Clear();
                _ed.Trim();
            }

            public void Cancel()
            {
                RequireActive();
                _finished = true; _ed._active = null;
                foreach (var kv in _saved) _layer.PasteRect(TileRectOf(kv.Key), kv.Value);
            }

            private void RequireActive()
            {
                if (_finished) throw new InvalidOperationException("The stroke is already finished");
            }

            private void SaveTiles(int left, int top, int right, int bottom)
            {
                int w = _layer.Width, h = _layer.Height;
                left = Math.Max(0, left); top = Math.Max(0, top);
                right = Math.Min(w, right); bottom = Math.Min(h, bottom);
                if (left >= right || top >= bottom) return;
                for (int ty = top / Tile; ty <= (bottom - 1) / Tile; ty++)
                    for (int tx = left / Tile; tx <= (right - 1) / Tile; tx++)
                    {
                        int key = ty * _tilesAcross + tx;
                        if (!_saved.ContainsKey(key)) _saved[key] = _layer.CopyRect(TileRectOf(key));
                    }
            }

            private Rect TileRectOf(int key) => TileRect(key, _tilesAcross, _layer.Width, _layer.Height);

            /// <summary>Keeps old pixels where the selection mask is clear, blends where it is partial.</summary>
            private void ApplyMask(int left, int top, int right, int bottom)
            {
                int w = _layer.Width, h = _layer.Height;
                left = Math.Max(0, left); top = Math.Max(0, top);
                right = Math.Min(w, right); bottom = Math.Min(h, bottom);
                if (left >= right || top >= bottom) return;
                int[] px = _layer.Pixels;
                for (int ty = top / Tile; ty <= (bottom - 1) / Tile; ty++)
                    for (int tx = left / Tile; tx <= (right - 1) / Tile; tx++)
                    {
                        if (!_saved.TryGetValue(ty * _tilesAcross + tx, out var saved)) continue;
                        int x0 = tx * Tile, y0 = ty * Tile;
                        int tw = Math.Min(w, x0 + Tile) - x0;
                        int xa = Math.Max(left, x0), xb = Math.Min(right, x0 + tw);
                        int ya = Math.Max(top, y0), yb = Math.Min(bottom, y0 + Tile);
                        for (int y = ya; y < yb; y++)
                            for (int x = xa; x < xb; x++)
                            {
                                int i = y * w + x;
                                int o = saved[(y - y0) * tw + (x - x0)];
                                int p = px[i];
                                int m = (int)((uint)_mask[i] >> 24);
                                if (p == o || m == 255) continue;
                                if (m == 0) { px[i] = o; continue; }
                                int oa = (int)((uint)o >> 24), pa = (int)((uint)p >> 24);
                                int a = oa + ((pa - oa) * m) / 255;
                                int r = ((o >> 16) & 0xFF) + ((((p >> 16) & 0xFF) - ((o >> 16) & 0xFF)) * m) / 255;
                                int g = ((o >> 8) & 0xFF) + ((((p >> 8) & 0xFF) - ((o >> 8) & 0xFF)) * m) / 255;
                                int b = (o & 0xFF) + (((p & 0xFF) - (o & 0xFF)) * m) / 255;
                                px[i] = (a << 24) | (r << 16) | (g << 8) | b;
                            }
                    }
            }

            /// <summary>Alpha lock: paint only where the layer already had pixels, keep its alpha.</summary>
            private void LockAlpha(int left, int top, int right, int bottom)
            {
                int w = _layer.Width, h = _layer.Height;
                left = Math.Max(0, left); top = Math.Max(0, top);
                right = Math.Min(w, right); bottom = Math.Min(h, bottom);
                if (left >= right || top >= bottom) return;
                int[] px = _layer.Pixels;
                for (int ty = top / Tile; ty <= (bottom - 1) / Tile; ty++)
                    for (int tx = left / Tile; tx <= (right - 1) / Tile; tx++)
                    {
                        if (!_saved.TryGetValue(ty * _tilesAcross + tx, out var saved)) continue;
                        int x0 = tx * Tile, y0 = ty * Tile;
                        int tw = Math.Min(w, x0 + Tile) - x0;
                        int xa = Math.Max(left, x0), xb = Math.Min(right, x0 + tw);
                        int ya = Math.Max(top, y0), yb = Math.Min(bottom, y0 + Tile);
                        for (int y = ya; y < yb; y++)
                            for (int x = xa; x < xb; x++)
                            {
                                int i = y * w + x;
                                int o = saved[(y - y0) * tw + (x - x0)];
                                int p = px[i];
                                if (p == o) continue;
                                int oa = (int)((uint)o >> 24), na = (int)((uint)p >> 24);
                                if (oa == 0) px[i] = 0;
                                else if (na == 0) px[i] = o;
                                else if (na != oa)
                                {
                                    int r = Math.Min(255, ((p >> 16) & 0xFF) * 255 / na);
                                    int g = Math.Min(255, ((p >> 8) & 0xFF) * 255 / na);
                                    int b = Math.Min(255, (p & 0xFF) * 255 / na);
                                    px[i] = PixelOps.Pack(oa, PixelOps.Div255(r * oa), PixelOps.Div255(g * oa), PixelOps.Div255(b * oa));
                                }
                            }
                    }
            }

            private Edit BuildEdit()
            {
                int n = _saved.Count;
                var keys = new int[n];
                var before = new int[n][];
                var after = new int[n][];
                int count = 0;
                Rect bounds = Rect.Empty;
                foreach (var kv in _saved)
                {
                    Rect r = TileRectOf(kv.Key);
                    int[] now = _layer.CopyRect(r);
                    if (now.AsSpan().SequenceEqual(kv.Value)) continue;
                    keys[count] = kv.Key; before[count] = kv.Value; after[count] = now; count++;
                    bounds = bounds.Union(r);
                }
                if (count == 0) return null;
                return new Edit(_layerIndex, _tilesAcross, keys.Take(count).ToArray(), before.Take(count).ToArray(),
                    after.Take(count).ToArray(), bounds, _brush.IsEraser ? KindErase : KindStroke);
            }
        }

        private sealed class Edit
        {
            public readonly int LayerIndex, Across;
            public readonly int[] Keys;
            public readonly int[][] Before, After;
            public readonly Rect Bounds;
            public readonly string Kind;
            public Action UndoOp, RedoOp;
            public object Key;

            public Edit(int layerIndex, int across, int[] keys, int[][] before, int[][] after, Rect bounds, string kind)
            {
                LayerIndex = layerIndex; Across = across; Keys = keys; Before = before; After = after; Bounds = bounds; Kind = kind;
            }

            public long Bytes()
            {
                long n = 0;
                for (int i = 0; i < Keys.Length; i++) n += (Before[i].Length + After[i].Length) * 4L;
                return n;
            }

            public void Apply(Layer layer, int[][] pixels)
            {
                for (int i = 0; i < Keys.Length; i++)
                    layer.PasteRect(TileRect(Keys[i], Across, layer.Width, layer.Height), pixels[i]);
            }
        }
    }
}
