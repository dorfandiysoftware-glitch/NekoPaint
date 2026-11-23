// SPDX-License-Identifier: GPL-3.0-or-later
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using Avalonia.Platform.Storage;
using NekoPaint.Core;
using CoreBrush = NekoPaint.Core.Brush;
using CoreRect = NekoPaint.Core.Rect;

namespace NekoPaint.Linux
{
    /// <summary>One row in the layers list. DocIndex is the index inside the document (0 = bottom).</summary>
    public sealed class LayerItem
    {
        public int DocIndex { get; set; }
        public string Name { get; set; }
        public bool Visible { get; set; }
        public string Display => Visible ? Name : Name + " (сховано)";
        public double ShownOpacity => Visible ? 1.0 : 0.5;
    }

    public partial class MainWindow : Window
    {
        private Document _doc;
        private Editor _ed;
        private Editor.Stroke _stroke;
        private int _active;
        private WriteableBitmap _bmp;
        private IStorageFile _file;

        private double _scale = 1, _tx, _ty;
        private bool _panning, _needFit = true, _suppress;
        private double _panX, _panY;
        private bool _eraser;
        private int _rgb;
        private string _posText = "";
        private int _paper = -1; // straight ARGB; 0 = transparent (check pattern)
        private double _lastRenderMs;

        public MainWindow()
        {
            AvaloniaXamlLoader_Load();
            BlendCombo.ItemsSource = Enum.GetNames(typeof(BlendMode));

            Viewport.PointerPressed += OnViewportDown;
            Viewport.PointerMoved += OnViewportMove;
            Viewport.PointerReleased += OnViewportUp;
            Viewport.PointerCaptureLost += (s, e) => EndStroke();
            Viewport.PointerWheelChanged += OnViewportWheel;
            Viewport.SizeChanged += (s, e) => { if (_needFit) FitToWindow(); };

            RedSlider.PropertyChanged += OnColorSlider;
            GreenSlider.PropertyChanged += OnColorSlider;
            BlueSlider.PropertyChanged += OnColorSlider;
            LayerOpacitySlider.PropertyChanged += OnLayerOpacityChanged;
            BlendCombo.SelectionChanged += OnBlendChanged;

            Opened += (s, e) => { if (_doc == null) NewDocument(2048, 1536); };
        }

        private void AvaloniaXamlLoader_Load() => Avalonia.Markup.Xaml.AvaloniaXamlLoader.Load(this);

        // ---------- document ----------

        private void NewDocument(int w, int h)
        {
            var d = new Document(w, h);
            d.AddLayer("Шар 1");
            _paper = -1;
            SetDocument(d, null);
        }

        private void SetDocument(Document d, IStorageFile file)
        {
            _stroke = null;
            _doc = d;
            _ed = new Editor(d, 100);
            _file = file;
            _active = d.LayerCount - 1;
            _bmp = new WriteableBitmap(new PixelSize(d.Width, d.Height), new Vector(96, 96),
                Avalonia.Platform.PixelFormat.Bgra8888, AlphaFormat.Premul);
            CanvasImage.Source = _bmp;
            Stage.Width = d.Width;
            Stage.Height = d.Height;
            FitToWindow();
            RebuildLayers();
            RenderAll();
            UpdateButtons();
        }

        private void RenderAll() => RenderRect(new CoreRect(0, 0, _doc.Width, _doc.Height));

        /// <summary>Composites a rectangle of the document into the bitmap (BGRA8 premultiplied = our ARGB ints).</summary>
        private void RenderRect(CoreRect r)
        {
            if (_doc == null || r.IsEmpty) return;
            var sw = Stopwatch.StartNew();
            int x0 = Math.Max(0, r.Left), y0 = Math.Max(0, r.Top);
            int x1 = Math.Min(_doc.Width, r.Right), y1 = Math.Min(_doc.Height, r.Bottom);
            if (x1 <= x0 || y1 <= y0) return;
            var area = new CoreRect(x0, y0, x1, y1);
            int w = area.Width, h = area.Height;
            var px = new int[w * h];
            _doc.FlattenRect(area, _paper, px);
            if (_paper == 0) // transparent paper: show a check pattern under the picture
            {
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++)
                    {
                        int i = y * w + x;
                        if (PixelOps.Alpha(px[i]) == 255) continue;
                        int c = (((x0 + x) >> 3) + ((y0 + y) >> 3)) % 2 == 0 ? unchecked((int)0xFFFFFFFF) : unchecked((int)0xFFCCCCCC);
                        px[i] = Blend.Apply(BlendMode.Normal, px[i], c);
                    }
            }
            using (var fb = _bmp.Lock())
            {
                for (int y = 0; y < h; y++)
                {
                    IntPtr dst = IntPtr.Add(fb.Address, (y0 + y) * fb.RowBytes + x0 * 4);
                    Marshal.Copy(px, y * w, dst, w);
                }
            }
            CanvasImage.InvalidateVisual();
            _lastRenderMs = sw.Elapsed.TotalMilliseconds;
        }

        // ---------- view ----------

        private void ApplyTransform()
        {
            Stage.RenderTransform = new MatrixTransform(new Matrix(_scale, 0, 0, _scale, _tx, _ty));
            RenderOptions.SetBitmapInterpolationMode(CanvasImage,
                _scale >= 1.5 ? BitmapInterpolationMode.None : BitmapInterpolationMode.LowQuality);
            UpdateStatus();
        }

        private void FitToWindow()
        {
            if (_doc == null) return;
            double vw = Viewport.Bounds.Width, vh = Viewport.Bounds.Height;
            if (vw < 10 || vh < 10) { _needFit = true; return; }
            _needFit = false;
            _scale = Math.Min(vw / _doc.Width, vh / _doc.Height) * 0.95;
            _tx = (vw - _doc.Width * _scale) / 2;
            _ty = (vh - _doc.Height * _scale) / 2;
            ApplyTransform();
        }

        private void OnFit(object sender, RoutedEventArgs e) => FitToWindow();

        private void OnViewportWheel(object sender, PointerWheelEventArgs e)
        {
            if (_doc == null) return;
            var pos = e.GetPosition(Viewport);
            double ns = Math.Max(0.02, Math.Min(64, _scale * Math.Pow(1.18, e.Delta.Y)));
            _tx = pos.X - (pos.X - _tx) * ns / _scale;
            _ty = pos.Y - (pos.Y - _ty) * ns / _scale;
            _scale = ns;
            ApplyTransform();
            e.Handled = true;
        }

        private void UpdateStatus()
        {
            if (StatusText == null || _doc == null) return;
            StatusText.Text = $"{_doc.Width}×{_doc.Height}   |   {Math.Round(_scale * 100)}%   |   ядро: " +
                (CoreInfo.NativeAvailable ? "C (libnekocore.so)" : "C# (запасне)") +
                $"   |   перемальовка {_lastRenderMs:0.0} мс   |   " + _posText;
        }

        // ---------- drawing ----------

        private CoreBrush CurrentBrush()
        {
            float radius = (float)Math.Max(0.5, SizeSlider.Value / 2.0);
            float hardness = (float)(HardnessSlider.Value / 100.0);
            float flow = (float)(FlowSlider.Value / 100.0);
            return _eraser ? new CoreBrush(0, radius, hardness, flow, 0.12f, true)
                           : new CoreBrush(_rgb, radius, hardness, flow, 0.12f, false);
        }

        private void AddPoint(PointerEventArgs e)
        {
            var p = e.GetCurrentPoint(Viewport);
            float pressure = e.Pointer.Type == PointerType.Pen ? Math.Max(0.02f, p.Properties.Pressure) : 1f;
            float x = (float)((p.Position.X - _tx) / _scale);
            float y = (float)((p.Position.Y - _ty) / _scale);
            _stroke.AddPoint(x, y, pressure);
            RenderRect(_stroke.TakeChanged());
        }

        private void OnViewportDown(object sender, PointerPressedEventArgs e)
        {
            if (_doc == null) return;
            var pt = e.GetCurrentPoint(Viewport);
            if (pt.Properties.IsMiddleButtonPressed || pt.Properties.IsRightButtonPressed)
            {
                _panning = true; _panX = pt.Position.X; _panY = pt.Position.Y;
                e.Pointer.Capture(Viewport);
                return;
            }
            if (!pt.Properties.IsLeftButtonPressed || _stroke != null) return;
            var layer = _doc.GetLayer(_active);
            if (!layer.Visible || layer.Locked)
            {
                _posText = "шар сховано або заблоковано";
                UpdateStatus();
                return;
            }
            e.Pointer.Capture(Viewport);
            _stroke = _ed.BeginStroke(_active, CurrentBrush());
            AddPoint(e);
        }

        private void OnViewportMove(object sender, PointerEventArgs e)
        {
            if (_doc == null) return;
            var pt = e.GetCurrentPoint(Viewport);
            if (_panning)
            {
                _tx += pt.Position.X - _panX; _ty += pt.Position.Y - _panY;
                _panX = pt.Position.X; _panY = pt.Position.Y;
                ApplyTransform();
                return;
            }
            if (_stroke != null) AddPoint(e);
            int dx = (int)Math.Floor((pt.Position.X - _tx) / _scale), dy = (int)Math.Floor((pt.Position.Y - _ty) / _scale);
            _posText = $"x {dx}, y {dy}";
            UpdateStatus();
        }

        private void OnViewportUp(object sender, PointerReleasedEventArgs e)
        {
            e.Pointer.Capture(null);
            _panning = false;
            EndStroke();
        }

        private void EndStroke()
        {
            if (_stroke == null) return;
            _stroke.End();
            _stroke = null;
            UpdateButtons();
        }

        // ---------- tools ----------

        private void OnPen(object sender, RoutedEventArgs e) { _eraser = false; PenButton.IsChecked = true; EraserButton.IsChecked = false; }
        private void OnEraser(object sender, RoutedEventArgs e) { _eraser = true; PenButton.IsChecked = false; EraserButton.IsChecked = true; }

        private void OnColorSlider(object sender, AvaloniaPropertyChangedEventArgs e)
        {
            if (e.Property != Avalonia.Controls.Primitives.RangeBase.ValueProperty) return;
            int r = (int)RedSlider.Value, g = (int)GreenSlider.Value, b = (int)BlueSlider.Value;
            _rgb = (r << 16) | (g << 8) | b;
            ColorSwatch.Background = new SolidColorBrush(Color.FromRgb((byte)r, (byte)g, (byte)b));
        }

        // ---------- paper ----------

        private void SetPaper(int argb)
        {
            _paper = argb;
            if (_doc != null) RenderAll();
        }

        private void OnPaperWhite(object sender, RoutedEventArgs e) => SetPaper(-1);
        private void OnPaperBlack(object sender, RoutedEventArgs e) => SetPaper(unchecked((int)0xFF000000));
        private void OnPaperTransparent(object sender, RoutedEventArgs e) => SetPaper(0);
        private void OnPaperBrushColor(object sender, RoutedEventArgs e) => SetPaper(unchecked((int)0xFF000000) | _rgb);

        // ---------- undo / redo ----------

        private void UpdateButtons()
        {
            if (_ed == null) return;
            UndoButton.IsEnabled = _ed.CanUndo;
            RedoButton.IsEnabled = _ed.CanRedo;
        }

        private void AfterHistory()
        {
            if (_active >= _doc.LayerCount) _active = _doc.LayerCount - 1;
            if (_ed.WasStructural) { RebuildLayers(); RenderAll(); }
            else RenderRect(_ed.LastChanged);
            SyncLayerControls();
            UpdateButtons();
        }

        private void DoUndo() { if (_ed != null && _stroke == null && _ed.Undo()) AfterHistory(); }
        private void DoRedo() { if (_ed != null && _stroke == null && _ed.Redo()) AfterHistory(); }
        private void OnUndo(object sender, RoutedEventArgs e) => DoUndo();
        private void OnRedo(object sender, RoutedEventArgs e) => DoRedo();

        protected override async void OnKeyDown(KeyEventArgs e)
        {
            base.OnKeyDown(e);
            if (!e.KeyModifiers.HasFlag(KeyModifiers.Control)) return;
            switch (e.Key)
            {
                case Key.Z: DoUndo(); e.Handled = true; break;
                case Key.Y: DoRedo(); e.Handled = true; break;
                case Key.S: e.Handled = true; await SaveFile(); break;
                case Key.O: e.Handled = true; await OpenFile(); break;
            }
        }

        // ---------- layers ----------

        private void RebuildLayers()
        {
            var items = new List<LayerItem>();
            for (int i = _doc.LayerCount - 1; i >= 0; i--)
            {
                var l = _doc.GetLayer(i);
                items.Add(new LayerItem { DocIndex = i, Name = l.Name, Visible = l.Visible });
            }
            _suppress = true;
            LayerList.ItemsSource = items;
            LayerList.SelectedIndex = _doc.LayerCount - 1 - _active;
            _suppress = false;
            SyncLayerControls();
        }

        private void SyncLayerControls()
        {
            if (_doc == null || _active < 0 || _active >= _doc.LayerCount) return;
            var l = _doc.GetLayer(_active);
            _suppress = true;
            LayerOpacitySlider.Value = Math.Round(l.Opacity * 100.0 / 255.0);
            BlendCombo.SelectedIndex = (int)l.BlendMode;
            VisibleCheck.IsChecked = l.Visible;
            AlphaLockCheck.IsChecked = l.AlphaLock;
            ClipCheck.IsChecked = l.Clipped;
            _suppress = false;
            MergeBtn.IsEnabled = _active >= 1;
            DeleteBtn.IsEnabled = _doc.LayerCount > 1;
            UpBtn.IsEnabled = _active < _doc.LayerCount - 1;
            DownBtn.IsEnabled = _active > 0;
        }

        private void OnLayerSelected(object sender, SelectionChangedEventArgs e)
        {
            if (_suppress || !(LayerList.SelectedItem is LayerItem item)) return;
            _active = item.DocIndex;
            SyncLayerControls();
        }

        private void LayersChanged() { RebuildLayers(); RenderAll(); UpdateButtons(); }

        private void OnAddLayer(object sender, RoutedEventArgs e)
        {
            if (_stroke != null) return;
            var l = _ed.AddLayer("Шар " + (_doc.LayerCount + 1), _active + 1);
            _active = _doc.IndexOf(l);
            LayersChanged();
        }

        private void OnDuplicateLayer(object sender, RoutedEventArgs e)
        {
            if (_stroke != null) return;
            var l = _ed.DuplicateLayer(_active);
            _active = _doc.IndexOf(l);
            LayersChanged();
        }

        private void OnMergeDown(object sender, RoutedEventArgs e)
        {
            if (_stroke != null || _active < 1) return;
            _ed.MergeDown(_active);
            _active--;
            LayersChanged();
        }

        private void OnDeleteLayer(object sender, RoutedEventArgs e)
        {
            if (_stroke != null || _doc.LayerCount <= 1) return;
            _ed.RemoveLayer(_active);
            _active = Math.Min(_active, _doc.LayerCount - 1);
            LayersChanged();
        }

        private void OnLayerUp(object sender, RoutedEventArgs e)
        {
            if (_stroke != null || _active >= _doc.LayerCount - 1) return;
            _ed.MoveLayer(_active, _active + 1);
            _active++;
            LayersChanged();
        }

        private void OnLayerDown(object sender, RoutedEventArgs e)
        {
            if (_stroke != null || _active <= 0) return;
            _ed.MoveLayer(_active, _active - 1);
            _active--;
            LayersChanged();
        }

        private void OnVisibleClicked(object sender, RoutedEventArgs e)
        {
            if (_suppress) return;
            _ed.SetLayerVisible(_active, VisibleCheck.IsChecked == true);
            LayersChanged();
        }

        private void OnAlphaLockClicked(object sender, RoutedEventArgs e)
        {
            if (_suppress) return;
            _ed.SetLayerAlphaLock(_active, AlphaLockCheck.IsChecked == true);
            UpdateButtons();
        }

        private void OnClipClicked(object sender, RoutedEventArgs e)
        {
            if (_suppress) return;
            _ed.SetLayerClipped(_active, ClipCheck.IsChecked == true);
            RenderAll(); UpdateButtons();
        }

        private void OnLayerOpacityChanged(object sender, AvaloniaPropertyChangedEventArgs e)
        {
            if (e.Property != Avalonia.Controls.Primitives.RangeBase.ValueProperty) return;
            if (_suppress || _doc == null) return;
            _ed.SetLayerOpacity(_active, (int)Math.Round(LayerOpacitySlider.Value * 255.0 / 100.0));
            RenderAll(); UpdateButtons();
        }

        private void OnBlendChanged(object sender, SelectionChangedEventArgs e)
        {
            if (_suppress || _doc == null || BlendCombo.SelectedIndex < 0) return;
            _ed.SetLayerBlendMode(_active, (BlendMode)BlendCombo.SelectedIndex);
            RenderAll(); UpdateButtons();
        }

        // ---------- files ----------

        private void OnCreateNew(object sender, RoutedEventArgs e)
        {
            int w = (int)Math.Max(64, Math.Min(8000, NewW.Value ?? 2048));
            int h = (int)Math.Max(64, Math.Min(8000, NewH.Value ?? 1536));
            NewDocument(w, h);
        }

        private async Task ShowMessage(string title, string text)
        {
            var close = new Button { Content = "OK", HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Right, Margin = new Thickness(0, 12, 0, 0) };
            var panel = new StackPanel { Margin = new Thickness(18), Spacing = 6 };
            panel.Children.Add(new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap });
            panel.Children.Add(close);
            var dlg = new Window
            {
                Title = title, Content = panel, Width = 520, SizeToContent = SizeToContent.Height,
                WindowStartupLocation = WindowStartupLocation.CenterOwner, CanResize = false
            };
            close.Click += (s, a) => dlg.Close();
            await dlg.ShowDialog(this);
        }

        private async void OnOpen(object sender, RoutedEventArgs e) => await OpenFile();
        private async void OnSave(object sender, RoutedEventArgs e) => await SaveFile();
        private async void OnExportPng(object sender, RoutedEventArgs e) => await ExportPng();

        private async Task OpenFile()
        {
            var files = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
            {
                Title = "Відкрити", AllowMultiple = false,
                FileTypeFilter = new[] { new FilePickerFileType("Малюнки") { Patterns = new[] { "*.ora", "*.png" } } }
            });
            if (files.Count == 0) return;
            var file = files[0];
            try
            {
                var ms = new MemoryStream();
                using (var s = await file.OpenReadAsync()) await s.CopyToAsync(ms);
                ms.Position = 0;
                if (file.Name.EndsWith(".png", StringComparison.OrdinalIgnoreCase))
                {
                    var img = PngCodec.Decode(ms);
                    var d = new Document(img.Width, img.Height);
                    var l = d.AddLayer(Path.GetFileNameWithoutExtension(file.Name));
                    l.PasteRect(new CoreRect(0, 0, img.Width, img.Height), img.Pixels);
                    _paper = -1;
                    SetDocument(d, null);
                }
                else
                {
                    _paper = -1;
                    SetDocument(OpenRaster.Read(ms), file);
                }
            }
            catch (Exception ex)
            {
                await ShowMessage("Не вдалося відкрити файл", ex.Message);
            }
        }

        private async Task SaveFile()
        {
            if (_doc == null || _stroke != null) return;
            var file = _file;
            if (file == null)
            {
                file = await StorageProvider.SaveFilePickerAsync(new FilePickerSaveOptions
                {
                    Title = "Зберегти", SuggestedFileName = "малюнок", DefaultExtension = "ora",
                    FileTypeChoices = new[] { new FilePickerFileType("OpenRaster") { Patterns = new[] { "*.ora" } } }
                });
                if (file == null) return;
            }
            try
            {
                var ms = new MemoryStream();
                OpenRaster.Write(_doc, ms, _paper);
                using (var s = await file.OpenWriteAsync())
                {
                    s.SetLength(0);
                    ms.Position = 0;
                    await ms.CopyToAsync(s);
                }
                _file = file;
                _posText = "збережено: " + file.Name;
                UpdateStatus();
            }
            catch (Exception ex)
            {
                await ShowMessage("Не вдалося зберегти", ex.Message);
            }
        }

        private async Task ExportPng()
        {
            if (_doc == null) return;
            var file = await StorageProvider.SaveFilePickerAsync(new FilePickerSaveOptions
            {
                Title = "Експорт PNG", SuggestedFileName = "малюнок", DefaultExtension = "png",
                FileTypeChoices = new[] { new FilePickerFileType("PNG") { Patterns = new[] { "*.png" } } }
            });
            if (file == null) return;
            try
            {
                var ms = new MemoryStream();
                PngCodec.Encode(_doc.Width, _doc.Height, _doc.Flatten(_paper), ms);
                using (var s = await file.OpenWriteAsync())
                {
                    s.SetLength(0);
                    ms.Position = 0;
                    await ms.CopyToAsync(s);
                }
                _posText = "експортовано: " + file.Name;
                UpdateStatus();
            }
            catch (Exception ex)
            {
                await ShowMessage("Не вдалося експортувати", ex.Message);
            }
        }

        // ---------- speed test ----------

        private static long Bench(bool allowNative)
        {
            var layer = new Layer(2048, 2048, "bench");
            var brush = CoreBrush.Pen(0x3366AA, 12f);
            var rnd = new Random(1);
            var sw = Stopwatch.StartNew();
            for (int i = 0; i < 3000; i++)
            {
                var r = new StrokeRasterizer(layer, brush, allowNative);
                float x = rnd.Next(100, 1900), y = rnd.Next(100, 1900);
                for (int k = 0; k < 6; k++)
                {
                    r.AddPoint(x, y, 1f);
                    x += rnd.Next(-30, 60); y += rnd.Next(-30, 60);
                }
            }
            return sw.ElapsedMilliseconds;
        }

        private async void OnBenchmark(object sender, RoutedEventArgs e)
        {
            var managed = await Task.Run(() => Bench(false));
            string text = $"Мазки, C# (запасний шлях): {managed} мс\n";
            if (CoreInfo.NativeAvailable)
            {
                var native = await Task.Run(() => Bench(true));
                text += $"Мазки, C-ядро (libnekocore.so): {native} мс (прискорення {(double)managed / Math.Max(1, native):0.0}x)\n";
            }
            else
            {
                text += "C-ядро (libnekocore.so) не завантажилось: поруч із NekoPaint немає libnekocore.so.\n";
            }
            if (_doc != null)
            {
                var sw = Stopwatch.StartNew();
                for (int i = 0; i < 5; i++) RenderAll();
                text += $"Повне перемальовування полотна {_doc.Width}×{_doc.Height}: {sw.ElapsedMilliseconds / 5.0:0.0} мс (злиття + копіювання в картинку)\n";
            }
            text += $"Процесор: {Environment.ProcessorCount} потоків, ОС: {RuntimeInformation.OSDescription}";
            await ShowMessage("Тест швидкості (3000 мазків, полотно 2048×2048)", text);
        }
    }
}
