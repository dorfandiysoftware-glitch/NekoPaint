// SPDX-License-Identifier: GPL-3.0-or-later
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Threading.Tasks;
using Microsoft.UI;
using Microsoft.UI.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using NekoPaint.Core;
using Windows.Storage;
using Windows.Storage.Pickers;
using CoreBrush = NekoPaint.Core.Brush;
using CoreRect = NekoPaint.Core.Rect;

namespace NekoPaint.App
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

    public sealed partial class MainWindow : Window
    {
        private Document _doc;
        private Editor _ed;
        private Editor.Stroke _stroke;
        private int _active;
        private WriteableBitmap _bmp;
        private Stream _bmpStream;
        private StorageFile _file;

        private double _scale = 1, _tx, _ty;
        private bool _panning, _needFit = true, _suppress;
        private double _panX, _panY;
        private bool _eraser;
        private int _rgb;
        private string _posText = "";
        private int _paper = -1; // straight ARGB; 0 = transparent (shown as a check pattern)

        public MainWindow()
        {
            InitializeComponent();
            Title = "NekoPaint";
            SystemBackdrop = new MicaBackdrop();
            ExtendsContentIntoTitleBar = true;
            SetTitleBar(TitleBar);
            AppWindow.Resize(new Windows.Graphics.SizeInt32(1400, 900));
            BlendCombo.ItemsSource = Enum.GetNames(typeof(BlendMode));
            Root.Loaded += (s, e) => NewDocument(2048, 1536);
        }

        // ---------- document ----------

        private void NewDocument(int w, int h)
        {
            var d = new Document(w, h);
            d.AddLayer("Шар 1");
            _paper = -1;
            SetDocument(d, null);
        }

        private void SetDocument(Document d, StorageFile file)
        {
            _doc = d;
            _ed = new Editor(d, 100);
            _stroke = null;
            _file = file;
            _active = d.LayerCount - 1;
            _bmp = new WriteableBitmap(d.Width, d.Height);
            _bmpStream = _bmp.PixelBuffer.AsStream();
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
            var row = new byte[w * 4];
            for (int y = 0; y < h; y++)
            {
                Buffer.BlockCopy(px, y * w * 4, row, 0, w * 4);
                _bmpStream.Position = ((long)(y0 + y) * _doc.Width + x0) * 4;
                _bmpStream.Write(row, 0, row.Length);
            }
            _bmp.Invalidate();
        }

        // ---------- view ----------

        private void ApplyTransform()
        {
            StageTransform.ScaleX = StageTransform.ScaleY = _scale;
            StageTransform.TranslateX = _tx;
            StageTransform.TranslateY = _ty;
            UpdateStatus();
        }

        private void FitToWindow()
        {
            if (_doc == null) return;
            double vw = Viewport.ActualWidth, vh = Viewport.ActualHeight;
            if (vw < 10 || vh < 10) { _needFit = true; return; }
            _needFit = false;
            _scale = Math.Min(vw / _doc.Width, vh / _doc.Height) * 0.95;
            _tx = (vw - _doc.Width * _scale) / 2;
            _ty = (vh - _doc.Height * _scale) / 2;
            ApplyTransform();
        }

        private void OnFit(object sender, RoutedEventArgs e) => FitToWindow();

        private void OnViewportSizeChanged(object sender, SizeChangedEventArgs e)
        {
            Viewport.Clip = new RectangleGeometry { Rect = new Windows.Foundation.Rect(0, 0, e.NewSize.Width, e.NewSize.Height) };
            if (_needFit) FitToWindow();
        }

        private void OnViewportWheel(object sender, PointerRoutedEventArgs e)
        {
            if (_doc == null) return;
            var pt = e.GetCurrentPoint(Viewport);
            double steps = pt.Properties.MouseWheelDelta / 120.0;
            double factor = Math.Pow(1.18, steps);
            double ns = Math.Max(0.02, Math.Min(64, _scale * factor));
            _tx = pt.Position.X - (pt.Position.X - _tx) * ns / _scale;
            _ty = pt.Position.Y - (pt.Position.Y - _ty) * ns / _scale;
            _scale = ns;
            ApplyTransform();
            e.Handled = true;
        }

        private void UpdateStatus()
        {
            if (StatusText == null || _doc == null) return;
            StatusText.Text = $"{_doc.Width}×{_doc.Height}   |   {Math.Round(_scale * 100)}%   |   ядро: " +
                (CoreInfo.NativeAvailable ? "C (nekocore.dll)" : "C# (запасне)") + "   |   " + _posText;
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

        private void AddPoints(PointerRoutedEventArgs e)
        {
            var list = e.GetIntermediatePoints(Viewport);
            for (int i = list.Count - 1; i >= 0; i--)
            {
                var p = list[i];
                float pressure = e.Pointer.PointerDeviceType == PointerDeviceType.Pen
                    ? Math.Max(0.02f, p.Properties.Pressure) : 1f;
                float x = (float)((p.Position.X - _tx) / _scale);
                float y = (float)((p.Position.Y - _ty) / _scale);
                _stroke.AddPoint(x, y, pressure);
            }
            RenderRect(_stroke.TakeChanged());
        }

        private void OnViewportDown(object sender, PointerRoutedEventArgs e)
        {
            if (_doc == null) return;
            var pt = e.GetCurrentPoint(Viewport);
            if (pt.Properties.IsMiddleButtonPressed || pt.Properties.IsRightButtonPressed)
            {
                _panning = true; _panX = pt.Position.X; _panY = pt.Position.Y;
                Viewport.CapturePointer(e.Pointer);
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
            Viewport.CapturePointer(e.Pointer);
            _stroke = _ed.BeginStroke(_active, CurrentBrush());
            AddPoints(e);
        }

        private void OnViewportMove(object sender, PointerRoutedEventArgs e)
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
            if (_stroke != null) AddPoints(e);
            int dx = (int)Math.Floor((pt.Position.X - _tx) / _scale), dy = (int)Math.Floor((pt.Position.Y - _ty) / _scale);
            _posText = $"x {dx}, y {dy}";
            UpdateStatus();
        }

        private void OnViewportUp(object sender, PointerRoutedEventArgs e)
        {
            Viewport.ReleasePointerCaptures();
            _panning = false;
            if (_stroke != null)
            {
                _stroke.End();
                _stroke = null;
                UpdateButtons();
            }
        }

        // ---------- tools ----------

        private void OnPen(object sender, RoutedEventArgs e) { _eraser = false; PenButton.IsChecked = true; EraserButton.IsChecked = false; }
        private void OnEraser(object sender, RoutedEventArgs e) { _eraser = true; PenButton.IsChecked = false; EraserButton.IsChecked = true; }

        private void OnColorChanged(ColorPicker sender, ColorChangedEventArgs args)
        {
            var c = args.NewColor;
            _rgb = (c.R << 16) | (c.G << 8) | c.B;
            ColorSwatch.Fill = new SolidColorBrush(c);
        }

        // ---------- paper ----------

        private void SetPaper(int argb)
        {
            _paper = argb;
            PaperButton.Flyout.Hide();
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
        private void OnUndoAccel(KeyboardAccelerator s, KeyboardAcceleratorInvokedEventArgs a) { DoUndo(); a.Handled = true; }
        private void OnRedoAccel(KeyboardAccelerator s, KeyboardAcceleratorInvokedEventArgs a) { DoRedo(); a.Handled = true; }

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

        private void LayersChanged()
        {
            RebuildLayers(); RenderAll(); UpdateButtons();
        }

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

        private void OnLayerOpacityChanged(object sender, Microsoft.UI.Xaml.Controls.Primitives.RangeBaseValueChangedEventArgs e)
        {
            if (_suppress || _doc == null) return;
            _ed.SetLayerOpacity(_active, (int)Math.Round(e.NewValue * 255.0 / 100.0));
            RenderAll(); UpdateButtons();
        }

        private void OnBlendChanged(object sender, SelectionChangedEventArgs e)
        {
            if (_suppress || _doc == null || BlendCombo.SelectedIndex < 0) return;
            _ed.SetLayerBlendMode(_active, (BlendMode)BlendCombo.SelectedIndex);
            RenderAll(); UpdateButtons();
        }

        // ---------- files ----------

        private void InitPicker(object picker) =>
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(this));

        private async void OnNewAccel(KeyboardAccelerator s, KeyboardAcceleratorInvokedEventArgs a) { a.Handled = true; await NewDialog(); }
        private async void OnOpenAccel(KeyboardAccelerator s, KeyboardAcceleratorInvokedEventArgs a) { a.Handled = true; await OpenFile(); }
        private async void OnSaveAccel(KeyboardAccelerator s, KeyboardAcceleratorInvokedEventArgs a) { a.Handled = true; await SaveFile(false); }
        private async void OnNew(object sender, RoutedEventArgs e) => await NewDialog();
        private async void OnOpen(object sender, RoutedEventArgs e) => await OpenFile();
        private async void OnSave(object sender, RoutedEventArgs e) => await SaveFile(false);
        private async void OnExportPng(object sender, RoutedEventArgs e) => await ExportPng();

        private async Task Message(string title, string text)
        {
            var dlg = new ContentDialog
            {
                Title = title, Content = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap },
                CloseButtonText = "OK", XamlRoot = Root.XamlRoot
            };
            await dlg.ShowAsync();
        }

        private async Task NewDialog()
        {
            var wBox = new NumberBox { Header = "Ширина, px", Value = 2048, Minimum = 64, Maximum = 8000, SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact };
            var hBox = new NumberBox { Header = "Висота, px", Value = 1536, Minimum = 64, Maximum = 8000, SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact };
            var panel = new StackPanel { Spacing = 12 };
            panel.Children.Add(wBox);
            panel.Children.Add(hBox);
            var dlg = new ContentDialog
            {
                Title = "Нове полотно", Content = panel, PrimaryButtonText = "Створити", CloseButtonText = "Скасувати",
                DefaultButton = ContentDialogButton.Primary, XamlRoot = Root.XamlRoot
            };
            if (await dlg.ShowAsync() != ContentDialogResult.Primary) return;
            int w = (int)Math.Max(64, Math.Min(8000, wBox.Value)), h = (int)Math.Max(64, Math.Min(8000, hBox.Value));
            NewDocument(w, h);
        }

        private async Task OpenFile()
        {
            var picker = new FileOpenPicker();
            InitPicker(picker);
            picker.FileTypeFilter.Add(".ora");
            picker.FileTypeFilter.Add(".png");
            var file = await picker.PickSingleFileAsync();
            if (file == null) return;
            try
            {
                using (var s = await file.OpenStreamForReadAsync())
                {
                    var ms = new MemoryStream();
                    await s.CopyToAsync(ms);
                    ms.Position = 0;
                    if (file.FileType.ToLowerInvariant() == ".png")
                    {
                        var img = PngCodec.Decode(ms);
                        var d = new Document(img.Width, img.Height);
                        var l = d.AddLayer(file.DisplayName);
                        l.PasteRect(new CoreRect(0, 0, img.Width, img.Height), img.Pixels);
                        SetDocument(d, null);
                    }
                    else
                    {
                        SetDocument(OpenRaster.Read(ms), file);
                    }
                }
            }
            catch (Exception ex)
            {
                await Message("Не вдалося відкрити файл", ex.Message);
            }
        }

        private async Task SaveFile(bool forceAs)
        {
            if (_doc == null || _stroke != null) return;
            var file = _file;
            if (file == null || forceAs)
            {
                var picker = new FileSavePicker { SuggestedFileName = "малюнок" };
                InitPicker(picker);
                picker.FileTypeChoices.Add("OpenRaster", new List<string> { ".ora" });
                file = await picker.PickSaveFileAsync();
                if (file == null) return;
            }
            try
            {
                var ms = new MemoryStream();
                OpenRaster.Write(_doc, ms, _paper);
                using (var s = await file.OpenStreamForWriteAsync())
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
                await Message("Не вдалося зберегти", ex.Message);
            }
        }

        private async Task ExportPng()
        {
            if (_doc == null) return;
            var picker = new FileSavePicker { SuggestedFileName = "малюнок" };
            InitPicker(picker);
            picker.FileTypeChoices.Add("PNG", new List<string> { ".png" });
            var file = await picker.PickSaveFileAsync();
            if (file == null) return;
            try
            {
                var ms = new MemoryStream();
                PngCodec.Encode(_doc.Width, _doc.Height, _doc.Flatten(_paper), ms);
                using (var s = await file.OpenStreamForWriteAsync())
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
                await Message("Не вдалося експортувати", ex.Message);
            }
        }

        // ---------- speed test ----------

        private static long Bench(bool allowNative)
        {
            var layer = new Layer(2048, 2048, "bench");
            var brush = Brush_(0x3366AA, 12f);
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
            sw.Stop();
            return sw.ElapsedMilliseconds;
        }

        private static CoreBrush Brush_(int rgb, float radius) => CoreBrush.Pen(rgb, radius);

        private async void OnBenchmark(object sender, RoutedEventArgs e)
        {
            var managed = await Task.Run(() => Bench(false));
            string text = $"C# (запасний шлях): {managed} мс\n";
            if (CoreInfo.NativeAvailable)
            {
                var native = await Task.Run(() => Bench(true));
                text += $"C-ядро (nekocore.dll): {native} мс\nприскорення: {(double)managed / Math.Max(1, native):0.0}x";
            }
            else
            {
                text += "C-ядро (nekocore.dll) не завантажилось: поруч із NekoPaint.exe немає nekocore.dll.";
            }
            await Message("Тест швидкості (3000 мазків, полотно 2048×2048)", text);
        }
    }
}
