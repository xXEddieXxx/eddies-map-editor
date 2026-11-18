using System;
using System.IO;
using Microsoft.Win32;

namespace EddiesSetup
{
    /// <summary>What the setup window shows; one at a time.</summary>
    public enum Page
    {
        /// <summary>Not yet known what is installed.</summary>
        Looking,
        Install,
        Progress,
        Done,
        Failed,
        /// <summary>This version is installed already: start it, or remove it.</summary>
        Installed,
        /// <summary>Asked whether to remove it.</summary>
        Uninstall,
        /// <summary>A newer version is installed: nothing to do.</summary>
        Newer
    }

    /// <summary>
    /// What the setup window shows and what the map maker chose, without a word of Burn: the
    /// window reads it and calls its actions, SetupApp fills it and does the work.
    /// </summary>
    public sealed class SetupModel
    {
        /// <summary>The program's name: its folder, its exe, its entry among Windows' programs.</summary>
        public const string Program = "Eddies Map Editor";

        public string Version = "";
        public string InstallFolder = DefaultFolder();
        public bool StartMenu = true;
        public bool Desktop = true;
        public Page Page = Page.Looking;
        /// <summary>0 to 100 while installing or removing.</summary>
        public int Percent;
        /// <summary>Whether the work under way, or done, is the removal.</summary>
        public bool Removing;
        /// <summary>Only the progress shows, without a question before or after: an update.</summary>
        public bool Passive;
        /// <summary>Cancel was pressed; the work is being stopped.</summary>
        public bool Cancelling;
        /// <summary>Why it failed, in Windows' words.</summary>
        public string Error = "";

        public Action Install = () => { };
        public Action Uninstall = () => { };
        /// <summary>Stops the work under way.</summary>
        public Action Cancel = () => { };

        /// <summary>Told after every change, on the window's thread.</summary>
        public event Action Changed;

        public void Change()
        {
            Changed?.Invoke();
        }

        public string Exe
        {
            get { return Path.Combine(InstallFolder, Program + ".exe"); }
        }

        /// <summary>
        /// Whether a shortcut of the editor is in this folder of Windows, eg the desktop, or in a
        /// folder there: its Start menu entry is in one of the program's name, an older one's in
        /// "Unknown".
        /// </summary>
        public static bool HasShortcut(Environment.SpecialFolder where)
        {
            try
            {
                var folder = Environment.GetFolderPath(where);
                return !string.IsNullOrEmpty(folder)
                    && Directory.GetFiles(folder, Program + ".lnk", SearchOption.AllDirectories).Length > 0;
            }
            catch (Exception)
            {
                return true;
            }
        }

        /// <summary>For the user alone, where no administrator is asked.</summary>
        public static string DefaultFolder()
        {
            return Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), Program);
        }

        /// <summary>
        /// A folder the map maker chose: one of the program's name as it is, any other as the
        /// folder to put the program's own folder in, so its files never mix with others.
        /// </summary>
        public static string FolderFor(string chosen)
        {
            var trimmed = chosen.TrimEnd('\\', '/');
            return string.Equals(Path.GetFileName(trimmed), Program, StringComparison.OrdinalIgnoreCase)
                ? trimmed
                : Path.Combine(chosen, Program);
        }

        /// <summary>
        /// Where the editor is installed now, as Windows' list of programs has it, so a newer
        /// setup offers the folder chosen before; null when it is not installed.
        /// </summary>
        public static string InstalledFolder()
        {
            const string uninstall = @"Software\Microsoft\Windows\CurrentVersion\Uninstall";
            foreach (var hive in new[] { Registry.CurrentUser, Registry.LocalMachine })
            {
                try
                {
                    using (var programs = hive.OpenSubKey(uninstall))
                    {
                        if (programs == null)
                        {
                            continue;
                        }
                        foreach (var name in programs.GetSubKeyNames())
                        {
                            using (var program = programs.OpenSubKey(name))
                            {
                                var folder = program?.GetValue("InstallLocation") as string;
                                if (Program.Equals(program?.GetValue("DisplayName") as string)
                                    && !string.IsNullOrWhiteSpace(folder)
                                    && File.Exists(Path.Combine(folder, Program + ".exe")))
                                {
                                    return folder.TrimEnd('\\');
                                }
                            }
                        }
                    }
                }
                catch (Exception)
                {
                    // a list that cannot be read has nothing to say
                }
            }
            return null;
        }
    }
}
