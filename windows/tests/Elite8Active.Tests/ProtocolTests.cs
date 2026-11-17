using Elite8Active.Protocol;

namespace Elite8Active.Tests;

/// <summary>Expected bytes are real frames from the Sound+ Bluetooth captures (2026-10-27), as in the Android app's GnpTest.</summary>
public class ProtocolTests
{
    private static byte[] Request(int seq, FrameType type, int cmd, int sub, string payload = "") =>
        Gnp.Build(Gnp.Buds, Gnp.App, seq, type, cmd, sub, Hex.Bytes(payload));

    [Fact]
    public void BuildsCapturedRequests()
    {
        Assert.Equal(Hex.Bytes("040903460203"), Request(0x03, FrameType.Read, Gnp.CmdIdent, Jabra.IdentFirmware));
        Assert.Equal(Hex.Bytes("04096f4713be01"), Request(0x6f, FrameType.Read, Gnp.CmdConfig, 0xBE, "01"));
        Assert.Equal(Hex.Bytes("04096c8813be0102"), Request(0x6c, FrameType.Write, Gnp.CmdConfig, 0xBE, "0102"));
        Assert.Equal(Hex.Bytes("0409af8813ef0001"), Request(0xaf, FrameType.Write, Gnp.CmdConfig, 0xEF, "0001"));
    }

    [Fact]
    public void BuildsCapturedEqWrite()
    {
        var frame = Gnp.Build(Gnp.Buds, Gnp.App, 0x48, FrameType.Write, Gnp.CmdConfig, Jabra.ConfigEq, Jabra.EqPayload(EqPreset.BassBoost.Gains));
        Assert.Equal("040948a8137d0500000000b4010e06c202eeff8a06760bb8000006292ee00000063d591000000828", Convert.ToHexString(frame).ToLowerInvariant());
    }

    [Fact]
    public void RecognisesTheSupportedModel()
    {
        // IDENT 11 as read from the earbuds: 11847, the ID Sound+ files this model under.
        Assert.Equal(Jabra.SupportedProductId, Jabra.ProductId(Hex.Bytes("472e")));
        Assert.True(Jabra.IsSupported(11847, "Jabra Elite 8 Active"));
        // A different product is refused even with a similar name, e.g. a later generation.
        Assert.False(Jabra.IsSupported(12345, "Jabra Elite 8 Active"));
        Assert.True(Jabra.IsSupported(null, "Jabra Elite 8 Active"));
        Assert.False(Jabra.IsSupported(null, "Jabra Elite 10"));
        Assert.Null(Jabra.ProductId(Hex.Bytes("47")));
    }

    [Fact]
    public void ParsesReplyHeader()
    {
        var reply = new Frame(Hex.Bytes("090403cc020305342e362e30"));
        Assert.Equal(FrameType.Reply, reply.Type);
        Assert.Equal(Gnp.Buds, reply.Src);
        Assert.Equal(0x03, reply.Seq);
        Assert.Equal("4.6.0", Jabra.LengthPrefixedString(reply.Payload));
    }

    [Fact]
    public void SplitsConcatenatedAndPartialFrames()
    {
        var reader = new FrameReader();
        var stream = Hex.Bytes("090403cc020305342e362e30" + "0904ac" + "c6fc00");
        Assert.Single(reader.Feed(stream.AsSpan(0, 14)));
        var rest = reader.Feed(stream.AsSpan(14));
        Assert.Single(rest);
        Assert.Equal(0xFC, rest[0].Cmd);
    }

    [Fact]
    public void ParsesBattery()
    {
        var bothOut = new EarbudLink(Linked: true, Primary: Side.Right);
        var report = Jabra.ParseBattery(Hex.Bytes("80500000021500 5a05 0055"));
        Assert.Equal(new BatteryReport(Primary: 80, Other: 85, Case: 90), report);
        Assert.Equal(new Battery(Left: 85, Right: 80, Case: 90), report.ForSides(bothOut));
        Assert.Null(Jabra.ParseBattery(Hex.Bytes("805f00000215005a050000")).ForSides(bothOut).Left);
    }

    [Fact]
    public void ParsesLinkAndMapsBatteryBySide()
    {
        // capture4: the right earbud came out of the case (100 %) while the left (75 %) held the
        // link, then the right took the link over and the report swapped order.
        Assert.Equal(new EarbudLink(true, Side.Left), Jabra.ParseLink(Hex.Bytes("070100")));
        Assert.Equal(new EarbudLink(true, Side.Right), Jabra.ParseLink(Hex.Bytes("070101")));
        var leftHolds = Jabra.ParseBattery(Hex.Bytes("804b0000021500 5a050064"));
        var rightHolds = Jabra.ParseBattery(Hex.Bytes("80640000021500 5a05004b"));
        Assert.Equal(new Battery(75, 100, 90), leftHolds.ForSides(Jabra.ParseLink(Hex.Bytes("070100"))));
        Assert.Equal(new Battery(75, 100, 90), rightHolds.ForSides(Jabra.ParseLink(Hex.Bytes("070101"))));

        // listen.log: the left earbud went into the case; its stale level is dropped.
        var leftInCase = Jabra.ParseLink(Hex.Bytes("070001"))!;
        Assert.False(leftInCase.IsOnline(Side.Left));
        Assert.True(leftInCase.IsOnline(Side.Right));
        Assert.Equal(new Battery(null, 85, 90), Jabra.ParseBattery(Hex.Bytes("80550000021500 5a050055")).ForSides(leftInCase));

        Assert.Null(Jabra.ParseLink(Hex.Bytes("04ef")));
    }

    [Fact]
    public void WritesBackTheEventSubscription()
    {
        Assert.Equal(Hex.Bytes("00000296"), Jabra.Subscription(Hex.Bytes("00000296")));
        Assert.Equal(Hex.Bytes("00000296"), Jabra.Subscription(Hex.Bytes("00000006")));
        Assert.Equal(Hex.Bytes("01"), Jabra.Subscription(Hex.Bytes("01")));
    }

    [Fact]
    public void ParsesEqReadBackAndMatchesPreset()
    {
        var gains = Jabra.ParseEqGains(Hex.Bytes("0500001e00b4010e06c21802eeff8a0676180bb800000629182ee00000063d28591000000828"));
        Assert.Equal(EqPreset.BassBoost.Gains, gains);
        Assert.Same(EqPreset.BassBoost, EqPreset.Matching(gains));
        Assert.Null(EqPreset.Matching([1, 2, 3, 4, 5]));
    }

    [Fact]
    public void ClampsCustomEqGains()
    {
        var payload = Jabra.EqPayload([999, -999, 0, 0, 0]);
        Assert.Equal(450, (short)((payload[6] << 8) | payload[7]));
        Assert.Equal(-450, (short)((payload[12] << 8) | payload[13]));
    }

    [Fact]
    public void ParsesTogglesAsReadFromTheEarbuds()
    {
        Assert.True(Toggle.ReduceWind.Parse(Hex.Bytes("0001")));
        Assert.False(Toggle.ReduceWind.Parse(Hex.Bytes("0000")));
        Assert.False(Toggle.Sidetone.Parse(Hex.Bytes("0100")));
        Assert.True(Toggle.Sidetone.Parse(Hex.Bytes("0003")));
        Assert.Equal(Hex.Bytes("0001"), Toggle.ReduceWind.WritePayload(true));
        // Spatial Sound carries a third byte that is not part of the on/off value.
        Assert.False(Toggle.SpatialSound.Parse(Hex.Bytes("040401")));
        Assert.False(Toggle.SpatialSound.Parse(Hex.Bytes("040400")));
        Assert.True(Toggle.SpatialSound.Parse(Hex.Bytes("050501")));
        Assert.True(Toggle.AutoAnswer.Parse(Hex.Bytes("0101")));
        Assert.False(Toggle.AutoPause.Parse(Hex.Bytes("0000")));
        Assert.True(Toggle.MuteReminder.Parse(Hex.Bytes("14")));
        Assert.Equal(Hex.Bytes("0101"), Toggle.AutoAnswer.WritePayload(true));
        Assert.Null(Toggle.WearingDetection.Parse(Hex.Bytes("07")));
    }

    [Fact]
    public void ParsesChoices()
    {
        Assert.Equal(0, Choice.SoundModeCycle.Parse(Hex.Bytes("03000102")));
        Assert.Equal(2, Choice.SoundModeCycle.Parse(Hex.Bytes("020102")));
        Assert.Equal("30 min", Choice.SleepMode.Options[Choice.SleepMode.Parse(Hex.Bytes("06"))!.Value].Label);
        Assert.Equal("Treble", Choice.InCallEq.Options[Choice.InCallEq.Parse(Hex.Bytes("02"))!.Value].Label);
        Assert.Null(Choice.Guidance.Parse(Hex.Bytes("07")));
    }

    [Fact]
    public void LevelsMapBothWays()
    {
        // HearThrough is stored reversed: 00 lets in the most.
        Assert.Equal(4, Jabra.HearThroughStep(0));
        Assert.Equal(0, Jabra.HearThroughValue(4));
        Assert.Equal(Hex.Bytes("00fd"), Jabra.SidetonePayload(on: true, level: -3));
        Assert.Equal(-9, Jabra.SidetoneLevel(Hex.Bytes("01f7")));
    }
}
