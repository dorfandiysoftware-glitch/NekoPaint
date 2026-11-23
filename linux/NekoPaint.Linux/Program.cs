// SPDX-License-Identifier: GPL-3.0-or-later
using System;
using System.Diagnostics;
using Avalonia;
using Avalonia.X11;
using Avalonia.Logging;

namespace NekoPaint.Linux
{
    internal static class Program
    {
        [STAThread]
        public static int Main(string[] args)
        {
            Trace.Listeners.Add(new ConsoleTraceListener(true));
            Console.Error.WriteLine("NekoPaint: start (DISPLAY=" + Environment.GetEnvironmentVariable("DISPLAY")
                + ", WAYLAND_DISPLAY=" + Environment.GetEnvironmentVariable("WAYLAND_DISPLAY")
                + ", renderer=" + (UseGl ? "gl" : "software") + ")");
            try
            {
                return BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);
            }
            catch (Exception e)
            {
                Console.Error.WriteLine("NekoPaint: fatal: " + e);
                return 1;
            }
        }

        // The app draws on the CPU anyway, so software presentation is the safe default in VMs without a GPU.
        // NEKOPAINT_GL=1 switches to GL/GLX.
        private static bool UseGl => Environment.GetEnvironmentVariable("NEKOPAINT_GL") == "1";

        public static AppBuilder BuildAvaloniaApp()
        {
            var b = AppBuilder.Configure<App>().UsePlatformDetect().WithInterFont()
                .LogToTrace(LogEventLevel.Information);
            if (!UseGl)
                b = b.With(new X11PlatformOptions { RenderingMode = new[] { X11RenderingMode.Software } });
            return b;
        }
    }
}
