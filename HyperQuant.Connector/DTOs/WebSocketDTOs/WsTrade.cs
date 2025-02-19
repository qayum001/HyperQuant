using System.Text.Json.Serialization;

namespace HyperQuant.Connector.DTOs.WebSocketDTOs;

public class WsTrade
{
    [JsonPropertyName("timestamp")]
    public long Timestamp { get; set; }

    [JsonPropertyName("tradeId")]
    public long TradeId { get; set; }

    [JsonPropertyName("amount")]
    public decimal Amount { get; set; }

    [JsonPropertyName("price")]
    public decimal Price { get; set; }

    public DateTimeOffset GetDateTime() => DateTimeOffset.FromUnixTimeSeconds(Timestamp);
}
