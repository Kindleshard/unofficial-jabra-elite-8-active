using System.Globalization;
using System.Runtime.InteropServices;

namespace Elite8Active.Bluetooth;

/// <summary>A Bluetooth device paired with this PC.</summary>
public sealed record PairedDevice(ulong Address, string Name)
{
    /// <summary>Only the end of the address, for logs.</summary>
    public string ShortAddress => FormatAddress(Address)[^5..];

    public static string FormatAddress(ulong address) =>
        string.Join(':', Enumerable.Range(0, 6).Select(i => ((address >> (40 - 8 * i)) & 0xFF).ToString("X2")));

    public static ulong ParseAddress(string text) =>
        ulong.Parse(text.Replace(":", "").Replace("-", ""), NumberStyles.HexNumber, CultureInfo.InvariantCulture);
}

/// <summary>What Windows knows about paired Bluetooth devices (the classic Win32 Bluetooth API).</summary>
public static class BluetoothDevices
{
    /// <summary>Every device this PC remembers, without searching for new ones.</summary>
    public static IReadOnlyList<PairedDevice> Paired()
    {
        var devices = new List<PairedDevice>();
        var search = new SearchParams
        {
            Size = Marshal.SizeOf<SearchParams>(),
            ReturnAuthenticated = true,
            ReturnRemembered = true,
            ReturnConnected = true,
        };
        var info = new DeviceInfo { Size = Marshal.SizeOf<DeviceInfo>() };
        var find = BluetoothFindFirstDevice(ref search, ref info);
        if (find == IntPtr.Zero) return devices;
        try
        {
            do
            {
                devices.Add(new PairedDevice(info.Address, info.Name ?? ""));
            } while (BluetoothFindNextDevice(find, ref info));
        }
        finally
        {
            BluetoothFindDeviceClose(find);
        }
        return devices;
    }

    /// <summary>Whether this PC has a Bluetooth radio that's switched on.</summary>
    public static bool RadioOn()
    {
        var search = new RadioSearchParams { Size = Marshal.SizeOf<RadioSearchParams>() };
        var find = BluetoothFindFirstRadio(ref search, out var radio);
        if (find == IntPtr.Zero) return false;
        CloseHandle(radio);
        BluetoothFindRadioClose(find);
        return true;
    }

    /// <summary>Whether a Bluetooth link to the device is up right now.</summary>
    public static bool IsConnected(ulong address)
    {
        var info = new DeviceInfo { Size = Marshal.SizeOf<DeviceInfo>(), Address = address };
        return BluetoothGetDeviceInfo(IntPtr.Zero, ref info) == 0 && info.Connected;
    }

    // BLUETOOTH_DEVICE_SEARCH_PARAMS
    [StructLayout(LayoutKind.Sequential)]
    internal struct SearchParams
    {
        public int Size;
        [MarshalAs(UnmanagedType.Bool)] public bool ReturnAuthenticated;
        [MarshalAs(UnmanagedType.Bool)] public bool ReturnRemembered;
        [MarshalAs(UnmanagedType.Bool)] public bool ReturnUnknown;
        [MarshalAs(UnmanagedType.Bool)] public bool ReturnConnected;
        [MarshalAs(UnmanagedType.Bool)] public bool IssueInquiry;
        public byte TimeoutMultiplier;
        public IntPtr Radio;
    }

    // BLUETOOTH_DEVICE_INFO (560 bytes)
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    internal struct DeviceInfo
    {
        public int Size;
        public ulong Address;
        public uint ClassOfDevice;
        [MarshalAs(UnmanagedType.Bool)] public bool Connected;
        [MarshalAs(UnmanagedType.Bool)] public bool Remembered;
        [MarshalAs(UnmanagedType.Bool)] public bool Authenticated;
        public SystemTime LastSeen;
        public SystemTime LastUsed;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 248)] public string? Name;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct SystemTime
    {
        public ushort Year, Month, DayOfWeek, Day, Hour, Minute, Second, Milliseconds;
    }

    // BLUETOOTH_FIND_RADIO_PARAMS
    [StructLayout(LayoutKind.Sequential)]
    private struct RadioSearchParams
    {
        public int Size;
    }

    [DllImport("bthprops.cpl", SetLastError = true)]
    private static extern IntPtr BluetoothFindFirstRadio(ref RadioSearchParams search, out IntPtr radio);

    [DllImport("bthprops.cpl", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool BluetoothFindRadioClose(IntPtr find);

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseHandle(IntPtr handle);

    [DllImport("bthprops.cpl", SetLastError = true)]
    private static extern IntPtr BluetoothFindFirstDevice(ref SearchParams search, ref DeviceInfo info);

    [DllImport("bthprops.cpl", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool BluetoothFindNextDevice(IntPtr find, ref DeviceInfo info);

    [DllImport("bthprops.cpl", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool BluetoothFindDeviceClose(IntPtr find);

    [DllImport("bthprops.cpl", SetLastError = true)]
    private static extern int BluetoothGetDeviceInfo(IntPtr radio, ref DeviceInfo info);
}
