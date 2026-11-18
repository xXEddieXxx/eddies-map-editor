using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using Microsoft.Win32;

namespace EddiesSetup
{
    /// <summary>
    /// The setup's one window, in the editor's colors, dark or light as Windows is set: what is
    /// installed and where, the progress, and what came of it. It shows the model and calls its
    /// actions; it knows nothing of how the installing is done.
    /// </summary>
    public partial class SetupWindow : Window
    {
        private readonly SetupModel model;
        private Action onMain = () => { };
        private Action onSecond = () => { };
        private Action onLink = () => { };

        public SetupWindow(SetupModel model)
            : this(model, WindowsIsDark())
        {
        }

        /// <summary>In the dark or the light colors, whatever Windows is set to: for a picture of it.</summary>
        public SetupWindow(SetupModel model, bool dark)
        {
            this.model = model;
            UseColors(dark);
            InitializeComponent();
            model.Changed += Render;
            Render();
        }

        /// <summary>The window's handle, which Burn asks for to put its own dialogs over it.</summary>
        public IntPtr Handle
        {
            get { return new WindowInteropHelper(this).EnsureHandle(); }
        }

        // the editor's colors: its dark theme's, or its light theme's
        private void UseColors(bool dark)
        {
            Set("Bg", dark ? "#1E1F22" : "#F3F4F6");
            Set("Panel", dark ? "#2B2D30" : "#FFFFFF");
            Set("Line", dark ? "#43454A" : "#D0D3D9");
            Set("Text", dark ? "#DFE1E5" : "#1F2328");
            Set("Soft", dark ? "#9A9DA3" : "#656D76");
            Set("Accent", dark ? "#3574F0" : "#2F6FEB");
            Set("Focus", dark ? "#589DF6" : "#2F6FEB");
        }

        private void Set(string name, string color)
        {
            var brush = new SolidColorBrush((Color)ColorConverter.ConvertFromString(color));
            brush.Freeze();
            Resources[name] = brush;
        }

        /// <summary>Whether Windows shows its apps dark; light where it does not say.</summary>
        private static bool WindowsIsDark()
        {
            try
            {
                using (var key = Registry.CurrentUser.OpenSubKey(
                    @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize"))
                {
                    return key?.GetValue("AppsUseLightTheme") is int light && light == 0;
                }
            }
            catch (Exception)
            {
                return false;
            }
        }

        /// <summary>Shows the model as it is now.</summary>
        private void Render()
        {
            VersionLine.Text = "Version " + model.Version + "  ·  Make maps for TripleA on one screen";
            InstallPage.Visibility = Shown(model.Page == Page.Install);
            ProgressPage.Visibility = Shown(model.Page == Page.Progress);
            MessagePage.Visibility = Shown(
                model.Page != Page.Install && model.Page != Page.Progress && model.Page != Page.Looking);
            NoteLine.Text = "";
            LinkButton.Visibility = Visibility.Collapsed;
            SecondButton.Visibility = Visibility.Visible;
            SecondButton.IsEnabled = !model.Cancelling;
            MainButton.Visibility = Visibility.Visible;
            onLink = () => { };
            switch (model.Page)
            {
                case Page.Looking:
                    SecondButton.Visibility = Visibility.Collapsed;
                    MainButton.Visibility = Visibility.Collapsed;
                    break;
                case Page.Install:
                    NoteLine.Text = "Installs for you alone. No administrator rights needed.";
                    FolderText.Text = model.InstallFolder;
                    FolderText.ToolTip = model.InstallFolder;
                    StartMenuBox.IsChecked = model.StartMenu;
                    DesktopBox.IsChecked = model.Desktop;
                    Buttons("Cancel", Close, "Install", StartInstall);
                    break;
                case Page.Progress:
                    ProgressTitle.Text = model.Removing ? "Removing…" : "Installing…";
                    ProgressNote.Text = model.Removing
                        ? "Your maps and settings stay."
                        : "Copying the editor and its Java to " + model.InstallFolder;
                    Bar.Value = model.Percent;
                    MainButton.Visibility = Visibility.Collapsed;
                    // an update shows its progress alone
                    SecondButton.Visibility = Shown(!model.Passive);
                    Buttons("Cancel", StopWork, "", () => { });
                    break;
                case Page.Done:
                    if (model.Removing)
                    {
                        Message(
                            "Eddie's Map Editor is removed",
                            "Your maps and the editor's settings stay where they are.");
                        SecondButton.Visibility = Visibility.Collapsed;
                        Buttons("", () => { }, "Close", Close);
                    }
                    else
                    {
                        Message("Eddie's Map Editor is installed", WhereToStart());
                        Buttons("Close", Close, "Start the editor", StartEditor);
                    }
                    break;
                case Page.Failed:
                    Message(
                        model.Removing ? "It could not be removed" : "The installation did not finish",
                        string.IsNullOrWhiteSpace(model.Error)
                            ? "Nothing was changed. Close this window and try again."
                            : model.Error.Trim());
                    SecondButton.Visibility = Visibility.Collapsed;
                    Buttons("", () => { }, "Close", Close);
                    break;
                case Page.Installed:
                    Message("This version is installed", "Its files are in " + model.InstallFolder + ".");
                    LinkButton.Content = "Uninstall";
                    LinkButton.Visibility = Visibility.Visible;
                    onLink = () =>
                    {
                        model.Page = Page.Uninstall;
                        model.Change();
                    };
                    Buttons("Close", Close, "Start the editor", StartEditor);
                    break;
                case Page.Uninstall:
                    Message(
                        "Remove Eddie's Map Editor?",
                        "The program goes, with its Start menu entry and its shortcut. Your maps and"
                            + " the editor's settings stay where they are.");
                    Buttons("Cancel", Close, "Uninstall", model.Uninstall);
                    break;
                case Page.Newer:
                    Message(
                        "A newer version is installed",
                        "This setup is of version " + model.Version + ". Nothing was changed.");
                    SecondButton.Visibility = Visibility.Collapsed;
                    Buttons("", () => { }, "Close", Close);
                    break;
            }
        }

        private static Visibility Shown(bool shown)
        {
            return shown ? Visibility.Visible : Visibility.Collapsed;
        }

        private void Message(string title, string text)
        {
            MessageTitle.Text = title;
            MessageText.Text = text;
        }

        private void Buttons(string second, Action onSecondClick, string main, Action onMainClick)
        {
            SecondButton.Content = second;
            onSecond = onSecondClick;
            MainButton.Content = main;
            onMain = onMainClick;
        }

        // where the map maker finds the editor afterwards, by what was ticked
        private string WhereToStart()
        {
            var places = model.StartMenu && model.Desktop
                ? "Start it from the Start menu or the desktop, under “" + SetupModel.Program + "”."
                : model.StartMenu
                    ? "Start it from the Start menu, under “" + SetupModel.Program + "”."
                    : model.Desktop
                        ? "Start it from the desktop, “" + SetupModel.Program + "”."
                        : "Start it with “" + SetupModel.Program + ".exe” in its folder.";
            return places + "\nIts files are in " + model.InstallFolder + ".";
        }

        private void StartInstall()
        {
            model.StartMenu = StartMenuBox.IsChecked == true;
            model.Desktop = DesktopBox.IsChecked == true;
            model.Install();
        }

        private void StopWork()
        {
            model.Cancelling = true;
            SecondButton.IsEnabled = false;
            model.Cancel();
        }

        private void StartEditor()
        {
            try
            {
                Process.Start(new ProcessStartInfo(model.Exe) { WorkingDirectory = model.InstallFolder });
            }
            catch (Exception)
            {
                // the page says where it is
            }
            Close();
        }

        private void OnChangeFolder(object sender, RoutedEventArgs e)
        {
            var chosen = FolderPicker.Pick(Handle, model.InstallFolder);
            if (chosen != null)
            {
                model.StartMenu = StartMenuBox.IsChecked == true;
                model.Desktop = DesktopBox.IsChecked == true;
                model.InstallFolder = SetupModel.FolderFor(chosen);
                Render();
            }
        }

        private void OnMain(object sender, RoutedEventArgs e)
        {
            onMain();
        }

        private void OnSecond(object sender, RoutedEventArgs e)
        {
            onSecond();
        }

        private void OnLink(object sender, RoutedEventArgs e)
        {
            onLink();
        }

        private void OnClose(object sender, RoutedEventArgs e)
        {
            Close();
        }

        // closing while it works stops the work; the window goes when that has ended
        protected override void OnClosing(CancelEventArgs e)
        {
            if (model.Page == Page.Progress)
            {
                e.Cancel = true;
                if (!model.Passive)
                {
                    StopWork();
                }
            }
            base.OnClosing(e);
        }
    }
}
