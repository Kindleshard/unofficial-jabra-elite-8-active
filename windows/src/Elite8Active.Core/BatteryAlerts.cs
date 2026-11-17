using Elite8Active.Protocol;

namespace Elite8Active;

/// <summary>Decides when a low-battery warning is due: once per part, until it has charged again.</summary>
public sealed class BatteryAlerts(IEnumerable<string> warned)
{
    /// <summary>Warn at this level or below, then not again until the part has charged past <see cref="Charged"/>.</summary>
    public const int Low = 10;
    private const int Charged = 20;

    private readonly HashSet<string> _warned = [.. warned];

    /// <summary>The parts already warned about, to keep across restarts.</summary>
    public IReadOnlyCollection<string> Warned => _warned;

    /// <summary>The parts that have just become low.</summary>
    public IReadOnlyList<(string Part, int Level)> NewlyLow(Battery battery)
    {
        var low = new List<(string, int)>();
        foreach (var (part, level) in new[] { ("Left earbud", battery.Left), ("Right earbud", battery.Right), ("Case", battery.Case) })
        {
            if (level is not { } percent) continue;
            if (percent <= Low && _warned.Add(part)) low.Add((part, percent));
            else if (percent >= Charged) _warned.Remove(part);
        }
        return low;
    }

    public static (string Title, string Text) Message(IReadOnlyList<(string Part, int Level)> low)
    {
        var title = low.Count == 1 ? $"{low[0].Part} battery low" : "Earbuds battery low";
        var text = string.Join(" · ", low.Select(p => $"{p.Part} {p.Level}%")) +
            (low.Any(p => p.Part == "Case") ? ". Charge the case soon." : ". Put them in the case to charge.");
        return (title, text);
    }
}
