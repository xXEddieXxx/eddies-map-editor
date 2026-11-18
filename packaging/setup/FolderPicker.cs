using System;
using System.IO;
using System.Runtime.InteropServices;

namespace EddiesSetup
{
    /// <summary>
    /// Windows' own dialog to choose a folder, the one Explorer shows, not the small tree of
    /// Windows XP that WPF and Windows Forms offer.
    /// </summary>
    internal static class FolderPicker
    {
        private const uint PickFolders = 0x20;
        private const uint ForceFileSystem = 0x40;
        private const uint FileSystemPath = 0x80058000;

        /// <summary>The folder chosen, starting at this one or its nearest parent; null when cancelled.</summary>
        public static string Pick(IntPtr owner, string start)
        {
            try
            {
                var dialog = (IFileDialog)new FileOpenDialog();
                dialog.GetOptions(out var options);
                dialog.SetOptions(options | PickFolders | ForceFileSystem);
                var from = start;
                while (!string.IsNullOrEmpty(from) && !Directory.Exists(from))
                {
                    from = Path.GetDirectoryName(from);
                }
                if (!string.IsNullOrEmpty(from)
                    && SHCreateItemFromParsingName(from, IntPtr.Zero, typeof(IShellItem).GUID, out var item) == 0)
                {
                    dialog.SetFolder(item);
                }
                if (dialog.Show(owner) != 0)
                {
                    return null;
                }
                dialog.GetResult(out var chosen);
                chosen.GetDisplayName(FileSystemPath, out var path);
                return path;
            }
            catch (Exception)
            {
                return null;
            }
        }

        [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = true)]
        private static extern int SHCreateItemFromParsingName(
            [MarshalAs(UnmanagedType.LPWStr)] string path,
            IntPtr bindContext,
            [MarshalAs(UnmanagedType.LPStruct)] Guid interfaceId,
            [MarshalAs(UnmanagedType.Interface)] out IShellItem item);

        [ComImport, Guid("DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7")]
        private class FileOpenDialog
        {
        }

        // IFileDialog's methods in their order; those not called stand for their place alone
        [ComImport, Guid("42F85136-DB7E-439C-85F1-E4075D135FC8"),
         InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IFileDialog
        {
            [PreserveSig]
            int Show(IntPtr owner);
            void SetFileTypes();
            void SetFileTypeIndex();
            void GetFileTypeIndex();
            void Advise();
            void Unadvise();
            void SetOptions(uint options);
            void GetOptions(out uint options);
            void SetDefaultFolder();
            void SetFolder([MarshalAs(UnmanagedType.Interface)] IShellItem folder);
            void GetFolder();
            void GetCurrentSelection();
            void SetFileName();
            void GetFileName();
            void SetTitle();
            void SetOkButtonLabel();
            void SetFileNameLabel();
            void GetResult([MarshalAs(UnmanagedType.Interface)] out IShellItem item);
        }

        [ComImport, Guid("43826D1E-E718-42EE-BC55-A1E261C37BFE"),
         InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IShellItem
        {
            void BindToHandler();
            void GetParent();
            void GetDisplayName(uint form, [MarshalAs(UnmanagedType.LPWStr)] out string name);
        }
    }
}
