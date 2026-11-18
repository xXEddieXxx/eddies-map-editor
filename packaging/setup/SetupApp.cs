using System;
using System.Windows.Threading;
using Microsoft.Tools.WindowsInstallerXml.Bootstrapper;

[assembly: BootstrapperApplication(typeof(EddiesSetup.SetupApp))]

namespace EddiesSetup
{
    /// <summary>
    /// What Burn runs as the interface of eddies-map-editor-setup.exe: it shows the window, tells
    /// Burn what was chosen, has it install or remove the editor's msi, and shows how far that is.
    /// Without a window it does what the command line asked and ends: so an older setup is
    /// removed by a newer one, and so "-quiet" installs.
    /// </summary>
    public sealed class SetupApp : BootstrapperApplication
    {
        // the msi's name in Bundle.wxs
        private const string Editor = "Editor";
        // an argument the editor's own update gives: the folder it is installed in
        private const string FolderArgument = "InstallFolder=";

        private readonly SetupModel model = new SetupModel();
        private Dispatcher dispatcher;
        private SetupWindow window;
        private IntPtr handle = IntPtr.Zero;
        private bool present;
        private bool newer;
        private bool cancelled;
        private int cached;
        private int executed;
        private int result;

        protected override void Run()
        {
            dispatcher = Dispatcher.CurrentDispatcher;
            var display = Command.Display;
            // with questions, or with the progress alone; any other way has no window
            var asking = display == Display.Full;
            model.Passive = display == Display.Passive;
            model.Version = Version();
            model.InstallFolder = Folder();
            if (SetupModel.InstalledFolder() != null)
            {
                // over an installed one: the shortcuts it has, ticked or not as they were
                model.StartMenu = SetupModel.HasShortcut(Environment.SpecialFolder.Programs);
                model.Desktop = SetupModel.HasShortcut(Environment.SpecialFolder.DesktopDirectory);
            }
            model.Install = () => Start(LaunchAction.Install);
            model.Uninstall = () => Start(LaunchAction.Uninstall);
            model.Cancel = () => cancelled = true;

            DetectPackageComplete += (sender, e) =>
            {
                if (e.PackageId == Editor)
                {
                    present = e.State == PackageState.Present;
                }
            };
            DetectRelatedBundle += (sender, e) =>
            {
                if (e.Operation == RelatedOperation.Downgrade)
                {
                    newer = true;
                }
            };
            DetectComplete += (sender, e) => OnWindow(() => Detected(asking, e.Status));
            PlanComplete += (sender, e) =>
            {
                if (e.Status >= 0)
                {
                    Engine.Apply(handle);
                }
                else
                {
                    OnWindow(() => Ended(asking, e.Status));
                }
            };
            CacheAcquireProgress += (sender, e) =>
            {
                cached = e.OverallPercentage;
                e.Result = Progressed();
            };
            ExecuteProgress += (sender, e) =>
            {
                executed = e.OverallPercentage;
                e.Result = Progressed();
            };
            Error += (sender, e) =>
            {
                // Windows' words for the last page; no box of Burn's own over the window
                model.Error = e.ErrorMessage ?? "";
            };
            ApplyComplete += (sender, e) => OnWindow(() => Ended(asking, e.Status));

            if (asking || model.Passive)
            {
                window = new SetupWindow(model);
                handle = window.Handle;
                window.Closed += (sender, e) => dispatcher.InvokeShutdown();
                window.Show();
            }
            Engine.Detect();
            Dispatcher.Run();
            Engine.Quit(result);
        }

        // the setup's version without a last ".0", as the editor writes its own
        private string Version()
        {
            try
            {
                var version = Engine.VersionVariables["WixBundleVersion"];
                return version.Revision > 0 ? version.ToString() : version.ToString(3);
            }
            catch (Exception)
            {
                return "";
            }
        }

        // where to install: as the command line says, else where it is installed, else for the user
        private string Folder()
        {
            foreach (var argument in Command.GetCommandLineArgs())
            {
                if (argument.StartsWith(FolderArgument, StringComparison.OrdinalIgnoreCase)
                    && argument.Length > FolderArgument.Length)
                {
                    return argument.Substring(FolderArgument.Length).Trim('"').TrimEnd('\\');
                }
            }
            return SetupModel.InstalledFolder() ?? SetupModel.DefaultFolder();
        }

        private void OnWindow(Action action)
        {
            dispatcher.BeginInvoke(action);
        }

        // what is installed is known: the page for it, or without questions the work at once
        private void Detected(bool asking, int status)
        {
            if (status < 0)
            {
                Ended(asking, status);
            }
            else if (!asking)
            {
                Start(Command.Action);
            }
            else if (Command.Action == LaunchAction.Uninstall)
            {
                Show(Page.Uninstall);
            }
            else if (newer)
            {
                Show(Page.Newer);
            }
            else
            {
                Show(present ? Page.Installed : Page.Install);
            }
        }

        private void Start(LaunchAction action)
        {
            model.Removing = action == LaunchAction.Uninstall;
            // the msi's properties for the folder and the shortcuts, see Bundle.wxs
            Engine.StringVariables["InstallFolder"] = model.InstallFolder;
            Engine.StringVariables["StartMenuShortcut"] = model.StartMenu ? "1" : "";
            Engine.StringVariables["DesktopShortcut"] = model.Desktop ? "1" : "";
            model.Percent = 0;
            Show(Page.Progress);
            Engine.Plan(action);
        }

        // caching and executing are a half each of the way
        private Result Progressed()
        {
            var percent = (cached + executed) / 2;
            OnWindow(() =>
            {
                if (model.Page == Page.Progress && percent != model.Percent)
                {
                    model.Percent = percent;
                    model.Change();
                }
            });
            return cancelled ? Result.Cancel : Result.None;
        }

        private void Ended(bool asking, int status)
        {
            result = status;
            if (!asking)
            {
                dispatcher.InvokeShutdown();
            }
            else if (status >= 0)
            {
                Show(Page.Done);
            }
            else if (cancelled)
            {
                // stopped by the map maker: nothing to say
                model.Page = Page.Looking;
                window.Close();
            }
            else
            {
                Show(Page.Failed);
            }
        }

        private void Show(Page page)
        {
            model.Page = page;
            model.Change();
        }
    }
}
