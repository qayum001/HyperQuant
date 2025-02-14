using HyperQuant.Connector.Queries;
using MediatR;
using Microsoft.Extensions.Logging;
using TestHQ;

namespace HyperQuant.Connector.Clients;

internal class RestConnectorClient(IMediator mediator, ILogger<RestConnectorClient> logger) : IRestConnectorClient
{
    public async Task<IEnumerable<Candle>> GetCandleSeriesAsync(string pair, int periodInSec, DateTimeOffset? from, DateTimeOffset? to = null, long? count = 0)
    {
        try
        {
            return await mediator
                .Send(new GetCandlesFromBitfinexQuery(pair, periodInSec, from, to, count));
        }
        catch (Exception ex)
        {
            logger.LogError($"Exception occurred while getting Candles: {ex.Message}");
            return [];
        }
    }

    public async Task<IEnumerable<Trade>> GetNewTradesAsync(string pair, int maxCount)
    {
        try
        {
            return await mediator
                .Send(new GetTradesFromBitfinexQuery(pair, maxCount));
        }
        catch (Exception ex)
        {
            logger.LogError($"Exception occurred while getting Trades: {ex.Message}");
            return [];
        }
    }
}