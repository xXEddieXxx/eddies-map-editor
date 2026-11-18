using System;
using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace EddiesSetup
{
    /// <summary>Writes a picture of each page of the setup window, dark and light.</summary>
    internal static class Program
    {
        [STAThread]
        private static int Main(string[] args)
        {
            var folder = args.Length > 0 ? args[0] : ".";
            Directory.CreateDirectory(folder);
            foreach (var dark in new[] { true, false })
            {
                foreach (Page page in Enum.GetValues(typeof(Page)))
                {
                    if (page == Page.Looking)
                    {
                        continue;
                    }
                    var model = new SetupModel
                    {
                        Version = "0.2.5",
                        InstallFolder = @"C:\Users\you\AppData\Local\Eddies Map Editor",
                        Page = page,
                        Percent = 62,
                        Error = "Windows Installer said: the folder cannot be written to (0x80070005)."
                    };
                    Save(new SetupWindow(model, dark), Path.Combine(folder, Name(page, dark)));
                }
            }
            return 0;
        }

        private static string Name(Page page, bool dark)
        {
            return "setup-" + page.ToString().ToLowerInvariant() + (dark ? "-dark" : "-light") + ".png";
        }

        // the window's inside, laid out at the window's size without the window being shown
        private static void Save(SetupWindow window, string file)
        {
            var inside = (FrameworkElement)window.Content;
            var size = new Size(window.Width, window.Height);
            inside.Measure(size);
            inside.Arrange(new Rect(size));
            inside.UpdateLayout();
            var picture = new RenderTargetBitmap(
                (int)size.Width * 2, (int)size.Height * 2, 192, 192, PixelFormats.Pbgra32);
            var ground = new DrawingVisual();
            using (var context = ground.RenderOpen())
            {
                context.DrawRectangle(window.Background, null, new Rect(size));
            }
            picture.Render(ground);
            picture.Render(inside);
            var png = new PngBitmapEncoder();
            png.Frames.Add(BitmapFrame.Create(picture));
            using (var stream = File.Create(file))
            {
                png.Save(stream);
            }
        }
    }
}
