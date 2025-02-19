namespace HyperQuant.Connector.DTOs.WebSocketDTOs;

internal class WsTradeSubscribeRequest
{
    public string Event { get; set; } = "subscribe";
    public string Channel { get; set; } = "trades";
    public string Symbol { get; set; } = "tBTCUSD";
}
