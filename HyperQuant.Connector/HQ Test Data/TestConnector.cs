using ConnectorTest;
using HyperQuant.Connector.Clients;
using TestHQ;

namespace HyperQuant.Connector.HQ_Test_Data;

internal class TestConnector : ITestConnector
{
    public event Action<Trade>? NewBuyTrade;
    public event Action<Trade>? NewSellTrade;
    public event Action<Candle>? CandleSeriesProcessing;

    private readonly IRestConnectorClient _restConnectorClient;
    private readonly IWebSocketConnectorClient _websocketConnectorClient;

    public TestConnector(IRestConnectorClient restConnectorClient, IWebSocketConnectorClient webSocketConnectorClient)
    {
        _restConnectorClient = restConnectorClient;
        _websocketConnectorClient = webSocketConnectorClient;

        webSocketConnectorClient.NewBuyTrade += (e) => NewBuyTrade?.Invoke(e);
        webSocketConnectorClient.NewSellTrade += (e) => NewSellTrade?.Invoke(e);
        webSocketConnectorClient.CandleSeriesProcessing += (e) => CandleSeriesProcessing?.Invoke(e);
    }

    public async Task<IEnumerable<Candle>> GetCandleSeriesAsync(string pair, int periodInSec, DateTimeOffset? from, DateTimeOffset? to = null, long? count = 0)
    {
        return await _restConnectorClient
            .GetCandleSeriesAsync(pair, periodInSec, from, to, count);
    }

    public async Task<IEnumerable<Trade>> GetNewTradesAsync(string pair, int maxCount)
    {
        return await _restConnectorClient.GetNewTradesAsync(pair, maxCount);
    }

    public void SubscribeCandles(string pair, int periodInSec, DateTimeOffset? from = null, DateTimeOffset? to = null, long? count = 0)
    {
        _websocketConnectorClient.SubscribeCandles(pair, periodInSec, from, to, count);
    }

    public void SubscribeTrades(string pair, int maxCount = 100)
    {
        _websocketConnectorClient.SubscribeTrades(pair, maxCount);
    }

    public void UnsubscribeCandles(string pair)
    {
        _websocketConnectorClient.UnsubscribeCandles(pair);
    }

    public void UnsubscribeTrades(string pair)
    {
        _websocketConnectorClient.UnsubscribeTrades(pair);
    }
}
