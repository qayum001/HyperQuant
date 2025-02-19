using TestHQ;

namespace HyperQuant.Connector.Clients;

internal interface IWebSocketConnectorClient
{
    public event Action<Trade> NewBuyTrade;
    public event Action<Trade> NewSellTrade;
    public event Action<Candle> CandleSeriesProcessing;

    void SubscribeTrades(string pair, int maxCount = 100);
    void UnsubscribeTrades(string pair);
    void SubscribeCandles(string pair, int periodInSec, DateTimeOffset? from = null, DateTimeOffset? to = null, long? count = 0);
    void UnsubscribeCandles(string pair);
}