using ConnectorTest;
using HyperQuant.Connector.Clients;
using HyperQuant.Connector.Queries;
using MediatR;
using TestHQ;

namespace HyperQuant.Connector.HQ_Test_Data;

internal class TestConnector(IRestConnectorClient restConnectorClient) : ITestConnector
{
    public event Action<Trade> NewBuyTrade;
    public event Action<Trade> NewSellTrade;
    public event Action<Candle> CandleSeriesProcessing;

    public async Task<IEnumerable<Candle>> GetCandleSeriesAsync(string pair, int periodInSec, DateTimeOffset? from, DateTimeOffset? to = null, long? count = 0)
    {
        return await restConnectorClient
            .GetCandleSeriesAsync(pair, periodInSec, from, to, count);
    }

    public async Task<IEnumerable<Trade>> GetNewTradesAsync(string pair, int maxCount)
    {
        return await restConnectorClient.GetNewTradesAsync(pair, maxCount);
    }

    public void SubscribeCandles(string pair, int periodInSec, DateTimeOffset? from = null, DateTimeOffset? to = null, long? count = 0)
    {
        throw new NotImplementedException();
    }

    public void SubscribeTrades(string pair, int maxCount = 100)
    {
        throw new NotImplementedException();
    }

    public void UnsubscribeCandles(string pair)
    {
        throw new NotImplementedException();
    }

    public void UnsubscribeTrades(string pair)
    {
        throw new NotImplementedException();
    }
}
