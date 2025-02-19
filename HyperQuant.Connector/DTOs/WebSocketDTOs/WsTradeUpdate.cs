using System.Text.Json.Serialization;
using TestHQ;

namespace HyperQuant.Connector.DTOs.WebSocketDTOs;

public class WsTradeUpdate
{
    public string ChannelId { get; set; } = string.Empty;

    public string UpdateType { get; set; } = string.Empty;

    public WsTrade Trade { get; set; } = new();
}
