using System.Runtime.InteropServices;

namespace Elite8Active.Bluetooth;

/// <summary>A connected byte stream to the earbuds.</summary>
public interface IByteChannel : IDisposable
{
    /// <summary>Blocks until bytes arrive; returns 0, or throws, once the link is gone.</summary>
    int Read(byte[] buffer);

    void Write(byte[] data);
}

/// <summary>
/// A Bluetooth RFCOMM connection through Winsock (AF_BTH), the same way Python's socket module
/// opens one. .NET's own Socket class doesn't accept the Bluetooth address family.
/// </summary>
public sealed class RfcommChannel : IByteChannel
{
    private const int AfBth = 32;
    private const int SockStream = 1;
    private const int BthProtoRfcomm = 3;
    private static readonly IntPtr InvalidSocket = new(-1);

    private IntPtr _socket;

    private RfcommChannel(IntPtr socket) => _socket = socket;

    /// <summary>Opens <paramref name="channel"/> on the device; blocks for a few seconds if it has to be paged.</summary>
    public static RfcommChannel Connect(ulong address, int channel)
    {
        EnsureStarted();
        var handle = socket(AfBth, SockStream, BthProtoRfcomm);
        if (handle == InvalidSocket) throw new IOException(Describe(WSAGetLastError()));
        var to = new SockAddrBth { Family = AfBth, Address = address, Port = (uint)channel };
        if (connect(handle, ref to, Marshal.SizeOf<SockAddrBth>()) != 0)
        {
            var error = WSAGetLastError();
            closesocket(handle);
            throw new IOException(Describe(error));
        }
        return new RfcommChannel(handle);
    }

    public int Read(byte[] buffer)
    {
        var n = recv(_socket, buffer, buffer.Length, 0);
        if (n < 0) throw new IOException(Describe(WSAGetLastError()));
        return n;
    }

    public void Write(byte[] data)
    {
        var sent = 0;
        while (sent < data.Length)
        {
            var n = send(_socket, sent == 0 ? data : data[sent..], data.Length - sent, 0);
            if (n <= 0) throw new IOException(Describe(WSAGetLastError()));
            sent += n;
        }
    }

    /// <summary>Closing the socket also ends a <see cref="Read"/> that's waiting on another thread.</summary>
    public void Dispose()
    {
        var handle = Interlocked.Exchange(ref _socket, InvalidSocket);
        if (handle != InvalidSocket) closesocket(handle);
    }

    private static string Describe(int error) => error switch
    {
        10048 => "Another program on this PC is already using the earbuds' control channel.",
        10050 or 10051 => "Bluetooth is off.",
        10060 or 10064 or 10065 => "The earbuds are out of reach or in the case.",
        10061 => "The earbuds refused the connection.",
        _ => $"Bluetooth error {error}.",
    };

    private static int _started;

    private static void EnsureStarted()
    {
        if (Interlocked.Exchange(ref _started, 1) == 1) return;
        var result = WSAStartup(0x0202, new byte[512]);
        if (result != 0) throw new IOException($"Winsock didn't start ({result}).");
    }

    // SOCKADDR_BTH from ws2bth.h, which is declared with 1-byte packing (30 bytes).
    [StructLayout(LayoutKind.Sequential, Pack = 1)]
    internal struct SockAddrBth
    {
        public ushort Family;
        public ulong Address;
        public Guid ServiceClassId;
        public uint Port;
    }

    [DllImport("ws2_32.dll")]
    private static extern int WSAStartup(ushort version, byte[] data);

    [DllImport("ws2_32.dll")]
    private static extern int WSAGetLastError();

    [DllImport("ws2_32.dll")]
    private static extern IntPtr socket(int family, int type, int protocol);

    [DllImport("ws2_32.dll")]
    private static extern int connect(IntPtr socket, ref SockAddrBth address, int length);

    [DllImport("ws2_32.dll")]
    private static extern int send(IntPtr socket, byte[] buffer, int length, int flags);

    [DllImport("ws2_32.dll")]
    private static extern int recv(IntPtr socket, byte[] buffer, int length, int flags);

    [DllImport("ws2_32.dll")]
    private static extern int closesocket(IntPtr socket);
}
