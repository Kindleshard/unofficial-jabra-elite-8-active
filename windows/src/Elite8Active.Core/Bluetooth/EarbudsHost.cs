namespace Elite8Active.Bluetooth;

/// <summary>What the app needs from the PC: which earbuds are paired, whether they're in use here, and a way in.</summary>
public interface IEarbudsHost
{
    /// <summary>Whether Bluetooth is switched on at all.</summary>
    bool BluetoothOn { get; }

    /// <summary>Every device paired with this PC; the earbuds can be among them under more than one address.</summary>
    IReadOnlyList<PairedDevice> PairedDevices();

    /// <summary>Whether the earbuds are connected to this PC (see <see cref="WindowsEarbudsHost.InUse"/>).</summary>
    bool IsInUse(PairedDevice device);

    /// <summary>Opens the control channel. Blocks; throws <see cref="IOException"/> with a readable reason.</summary>
    IByteChannel Open(PairedDevice device);
}

public sealed class WindowsEarbudsHost : IEarbudsHost
{
    /// <summary>The earbuds have two serial-port records; only "SPP Dev" on channel 29 speaks GNP.</summary>
    public const int GnpChannel = 29;

    public bool BluetoothOn => BluetoothDevices.RadioOn();

    public IReadOnlyList<PairedDevice> PairedDevices() => BluetoothDevices.Paired();

    public bool IsInUse(PairedDevice device) =>
        InUse(device.Name, AudioEndpoints.Outputs(), () => BluetoothDevices.IsConnected(device.Address));

    public IByteChannel Open(PairedDevice device) => RfcommChannel.Connect(device.Address, GnpChannel);

    /// <summary>
    /// The app follows Windows instead of reaching for the earbuds itself: it connects while they
    /// are one of this PC's audio outputs, and lets go when they stop being one (taken by the case,
    /// or "Disconnect" in Bluetooth settings). Opening the channel on its own would hold a link to
    /// earbuds that are meant to be with the phone only.
    ///
    /// The Bluetooth link itself only decides when their audio outputs can't (none listed under
    /// that name, or all disabled in Sound settings), because the app's own channel keeps it up.
    /// </summary>
    public static bool InUse(string deviceName, IReadOnlyList<AudioEndpoint> outputs, Func<bool> bluetoothConnected)
    {
        var theirs = outputs.Where(o => o.Name.Contains(deviceName, StringComparison.OrdinalIgnoreCase)).ToList();
        if (theirs.Any(o => o.State == AudioEndpointState.Active)) return true;
        var undecided = theirs.Count == 0 || theirs.Any(o => o.State == AudioEndpointState.Disabled);
        return undecided && bluetoothConnected();
    }
}
