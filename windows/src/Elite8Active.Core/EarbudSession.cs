using System.Collections.Concurrent;
using Elite8Active.Bluetooth;
using Elite8Active.Protocol;

namespace Elite8Active;

public sealed class GnpNackException(int code, string message) : IOException(message)
{
    public int Code { get; } = code;
}

/// <summary>GNP over an open channel. Replies are matched to requests by sequence number.</summary>
public sealed class EarbudSession : IDisposable
{
    private readonly IByteChannel _channel;
    private readonly ConcurrentDictionary<int, TaskCompletionSource<Frame>> _pending = new();
    private readonly object _sendGate = new();
    private int _nextSeq;
    private volatile bool _open = true;

    public EarbudSession(IByteChannel channel)
    {
        _channel = channel;
        new Thread(ReadLoop) { IsBackground = true, Name = "GNP reader" }.Start();
    }

    /// <summary>Unsolicited frames (battery updates, setting-changed notifications). Raised on the reader thread.</summary>
    public event Action<Frame>? EventReceived;

    /// <summary>The link dropped or the session was disposed. Raised once, on the reader thread.</summary>
    public event Action? Closed;

    public bool IsOpen => _open;

    private void ReadLoop()
    {
        var reader = new FrameReader();
        var buffer = new byte[1024];
        try
        {
            while (_open)
            {
                var n = _channel.Read(buffer);
                if (n <= 0) break;
                foreach (var frame in reader.Feed(buffer.AsSpan(0, n))) Dispatch(frame);
            }
        }
        catch (IOException)
        {
            // Socket closed or link lost.
        }
        finally
        {
            _open = false;
            foreach (var waiting in _pending.Values) waiting.TrySetException(new IOException("The connection closed."));
            _pending.Clear();
            Closed?.Invoke();
        }
    }

    private void Dispatch(Frame frame)
    {
        if (frame.Type == FrameType.Reply)
        {
            if (_pending.TryRemove(frame.Seq, out var waiting)) waiting.TrySetResult(frame);
        }
        else
        {
            EventReceived?.Invoke(frame);
        }
    }

    public async Task<Frame> RequestAsync(FrameType type, int cmd, int sub, byte[]? payload = null, int timeoutMs = 3000)
    {
        if (!_open) throw new IOException("Not connected.");
        // Continuations must not run on the reader thread, or a handler awaiting a second reply would block it.
        var reply = new TaskCompletionSource<Frame>(TaskCreationOptions.RunContinuationsAsynchronously);
        var seq = await Task.Run(() =>
        {
            lock (_sendGate)
            {
                // Events use seq 0, so requests cycle through 1..255.
                _nextSeq = _nextSeq % 255 + 1;
                _pending[_nextSeq] = reply;
                try
                {
                    _channel.Write(Gnp.Build(Gnp.Buds, Gnp.App, _nextSeq, type, cmd, sub, payload));
                }
                catch
                {
                    _pending.TryRemove(_nextSeq, out _);
                    throw;
                }
                return _nextSeq;
            }
        }).ConfigureAwait(false);

        Frame frame;
        try
        {
            frame = await reply.Task.WaitAsync(TimeSpan.FromMilliseconds(timeoutMs)).ConfigureAwait(false);
        }
        catch (TimeoutException)
        {
            throw new IOException("The earbuds didn't answer.");
        }
        finally
        {
            _pending.TryRemove(seq, out _);
        }

        if (frame.Cmd == Gnp.CmdNack)
        {
            var code = frame.Sub ?? 0;
            var reason = NackReasons.GetValueOrDefault(code, $"error 0x{code:x2}");
            throw new GnpNackException(code, $"The earbuds refused the change ({reason}).");
        }
        return frame;
    }

    public async Task<byte[]> ReadAsync(int cmd, int sub, byte[]? payload = null) =>
        (await RequestAsync(FrameType.Read, cmd, sub, payload).ConfigureAwait(false)).Payload;

    public async Task WriteAsync(int cmd, int sub, byte[] payload)
    {
        var reply = await RequestAsync(FrameType.Write, cmd, sub, payload).ConfigureAwait(false);
        if (reply.Cmd != Gnp.CmdAck) throw new IOException($"Expected ACK, got {reply}");
    }

    public void Dispose()
    {
        _open = false;
        _channel.Dispose();
    }

    private static readonly Dictionary<int, string> NackReasons = new()
    {
        [0xF3] = "bad state",
        [0xF5] = "illegal address",
        [0xF6] = "unknown command",
        [0xF8] = "internal error",
        [0xFA] = "illegal value",
        [0xFB] = "out of sequence",
        [0xFD] = "access denied",
        [0xFF] = "unknown setting",
    };
}

/// <summary>Reads that take more than one request, shared by the controller and the tests.</summary>
public static class EarbudReads
{
    /// <summary>
    /// Which earbud holds the link to this PC and whether the other one is linked to it. The
    /// earbuds only send this as an event, but they send it whenever the event subscription is
    /// written, so this writes the subscription back (as Sound+ does on every start) and waits
    /// for the event. Null if they don't send one.
    /// </summary>
    public static async Task<EarbudLink?> ReadLinkAsync(this EarbudSession session)
    {
        var seen = new TaskCompletionSource<EarbudLink>(TaskCreationOptions.RunContinuationsAsynchronously);
        void OnEvent(Frame frame)
        {
            if (frame.Cmd == Gnp.CmdDevice && frame.Sub == Jabra.DeviceEvents && Jabra.ParseLink(frame.Payload) is { } link)
            {
                seen.TrySetResult(link);
            }
        }

        // Listen before writing, so the event can't slip past.
        session.EventReceived += OnEvent;
        try
        {
            var current = await session.ReadAsync(Gnp.CmdDevice, Jabra.DeviceEvents).ConfigureAwait(false);
            await session.WriteAsync(Gnp.CmdDevice, Jabra.DeviceEvents, Jabra.Subscription(current)).ConfigureAwait(false);
            return await seen.Task.WaitAsync(TimeSpan.FromMilliseconds(1500)).ConfigureAwait(false);
        }
        catch (TimeoutException)
        {
            return null;
        }
        finally
        {
            session.EventReceived -= OnEvent;
        }
    }

    public static async Task<BatteryReport> ReadBatteryReportAsync(this EarbudSession session) =>
        Jabra.ParseBattery(await session.ReadAsync(Gnp.CmdStatus, Jabra.StatusBattery).ConfigureAwait(false));

    public static async Task<SoundMode?> ReadSoundModeAsync(this EarbudSession session) =>
        SoundModes.Of((await session.ReadAsync(Gnp.CmdConfig, Jabra.ConfigSoundMode, [Jabra.SoundModeSlot]).ConfigureAwait(false))[0]);

    public static Task WriteSoundModeAsync(this EarbudSession session, SoundMode mode) =>
        session.WriteAsync(Gnp.CmdConfig, Jabra.ConfigSoundMode, [Jabra.SoundModeSlot, (byte)mode]);
}
