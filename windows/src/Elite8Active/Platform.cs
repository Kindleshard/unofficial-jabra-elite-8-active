using System.IO;
using Microsoft.Win32;

namespace Elite8Active.Tray;

/// <summary>Where the app keeps its files.</summary>
public static class AppFolders
{
    /// <summary>settings.json and the last values read from the earbuds.</summary>
    public static string Data { get; } = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Elite8Active");

    public static string StateCache => Path.Combine(Data, "last_state.json");

    public static string Log { get; } = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Elite8Active", "log.txt");
}

/// <summary>A small text log of connection events, for when something doesn't connect.</summary>
public static class Log
{
    private const long MaxBytes = 256 * 1024;
    private static readonly object Gate = new();

    public static void Write(string message)
    {
        lock (Gate)
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(AppFolders.Log)!);
                // Start over when it gets long; only the recent past is of any use.
                if (File.Exists(AppFolders.Log) && new FileInfo(AppFolders.Log).Length > MaxBytes) File.Delete(AppFolders.Log);
                File.AppendAllText(AppFolders.Log, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {message}{Environment.NewLine}");
            }
            catch (IOException)
            {
                // Logging must never get in the way.
            }
        }
    }
}

/// <summary>"Start with Windows": an entry under the current user's Run key, nothing system-wide.</summary>
public static class Autostart
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string Name = "Elite8Active";

    /// <summary>Started this way, the app goes straight to the notification area without opening the panel.</summary>
    public const string BackgroundSwitch = "--background";

    public static string Command(string exePath) => $"\"{exePath}\" {BackgroundSwitch}";

    public static bool IsEnabled
    {
        get
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey);
            return key?.GetValue(Name) is string command && Environment.ProcessPath is { } exe &&
                command.Equals(Command(exe), StringComparison.OrdinalIgnoreCase);
        }
    }

    public static void Set(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey);
        if (enabled && Environment.ProcessPath is { } exe) key.SetValue(Name, Command(exe));
        else key.DeleteValue(Name, throwOnMissingValue: false);
    }
}
