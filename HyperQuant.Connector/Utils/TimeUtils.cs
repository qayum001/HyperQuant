namespace HyperQuant.Connector.Utils;

internal static class TimeUtils
{
    public static string GetPeriodFromSeconds(int seconds)
    {
        var periods = new Dictionary<int, string>
        {
            { 60, "1m" }, { 300, "5m" }, { 900, "15m" }, { 1800, "30m" },
            { 3600, "1h" }, { 10800, "3h" }, { 21600, "6h" }, { 43200, "12h" },
            { 86400, "1D" }, { 604800, "1W" }, { 1209600, "14D" }, { 2592000, "1M" }
        };

        return periods.OrderBy(p => Math.Abs(p.Key - seconds)).First().Value;
    }
}
