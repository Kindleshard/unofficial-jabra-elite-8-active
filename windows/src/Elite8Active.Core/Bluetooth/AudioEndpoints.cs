using System.Runtime.InteropServices;

namespace Elite8Active.Bluetooth;

/// <summary>One of Windows' audio outputs, e.g. "Headphones (Jabra Elite 8 Active)".</summary>
public sealed record AudioEndpoint(string Name, AudioEndpointState State);

public enum AudioEndpointState { Active = 1, Disabled = 2, NotPresent = 4, Unplugged = 8 }

/// <summary>
/// Windows' audio outputs, from the Core Audio device list. A Bluetooth headset's outputs are
/// "active" exactly while it's connected to this PC for audio.
/// </summary>
public static class AudioEndpoints
{
    public static IReadOnlyList<AudioEndpoint> Outputs()
    {
        var endpoints = new List<AudioEndpoint>();
        var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumerator();
        try
        {
            const int render = 0, everyState = 0xF;
            if (enumerator.EnumAudioEndpoints(render, everyState, out var collection) != 0) return endpoints;
            collection.GetCount(out var count);
            for (var i = 0; i < count; i++)
            {
                if (collection.Item(i, out var device) != 0) continue;
                device.GetState(out var state);
                const int read = 0;
                if (device.OpenPropertyStore(read, out var store) != 0) continue;
                var key = FriendlyName;
                if (store.GetValue(ref key, out var value) == 0)
                {
                    if (value.Type == VtLpwstr && Marshal.PtrToStringUni(value.Pointer) is { } name)
                    {
                        endpoints.Add(new AudioEndpoint(name, (AudioEndpointState)state));
                    }
                    PropVariantClear(ref value);
                }
                Marshal.ReleaseComObject(store);
                Marshal.ReleaseComObject(device);
            }
            Marshal.ReleaseComObject(collection);
        }
        finally
        {
            Marshal.ReleaseComObject(enumerator);
        }
        return endpoints;
    }

    private const ushort VtLpwstr = 31;

    // PKEY_Device_FriendlyName
    private static readonly PropertyKey FriendlyName = new() { FormatId = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0"), PropertyId = 14 };

    [StructLayout(LayoutKind.Sequential)]
    private struct PropertyKey
    {
        public Guid FormatId;
        public int PropertyId;
    }

    // PROPVARIANT: a type tag, then a 16-byte union of which only the string pointer is read here.
    [StructLayout(LayoutKind.Explicit, Size = 24)]
    private struct PropVariant
    {
        [FieldOffset(0)] public ushort Type;
        [FieldOffset(8)] public IntPtr Pointer;
    }

    [DllImport("ole32.dll")]
    private static extern int PropVariantClear(ref PropVariant value);

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    private class MMDeviceEnumerator;

    // Only the methods up to the last one used are declared; the order is the COM vtable's.
    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IMMDeviceCollection devices);
    }

    [ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceCollection
    {
        [PreserveSig] int GetCount(out int count);
        [PreserveSig] int Item(int index, out IMMDevice device);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice
    {
        [PreserveSig] int Activate(ref Guid interfaceId, int context, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object instance);
        [PreserveSig] int OpenPropertyStore(int access, out IPropertyStore store);
        [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        [PreserveSig] int GetState(out int state);
    }

    [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IPropertyStore
    {
        [PreserveSig] int GetCount(out int count);
        [PreserveSig] int GetAt(int index, out PropertyKey key);
        [PreserveSig] int GetValue(ref PropertyKey key, out PropVariant value);
    }
}
