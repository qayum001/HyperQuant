using HyperQuant.Connector.Utils;
using MediatR;
using RestSharp;
using TestHQ;

namespace HyperQuant.Connector.Queries;

internal record GetCandlesFromBitfinexQuery(string Pair,
    int PeriodInSec,
    DateTimeOffset? From,
    DateTimeOffset? To = null,
    long? Count = 0) 
    : IRequest<IEnumerable<Candle>>;

internal class GetCandlesFromBitfinexQueryHandler(IHttpClientFactory httpClientFactory) : IRequestHandler<GetCandlesFromBitfinexQuery, IEnumerable<Candle>>
{
    public async Task<IEnumerable<Candle>> Handle(GetCandlesFromBitfinexQuery request, CancellationToken cancellationToken)
    {
        var client = httpClientFactory.CreateClient("Bitfinix");

        var rClient = new RestClient(client);

        var rRequest = new RestRequest($"candles/trade:{TimeUtils.GetPeriodFromSeconds(request.PeriodInSec)}:t{request.Pair}/hist");

        if (request.From is not null)
            rRequest.AddParameter("start", request.From.ToString());

        if (request.To is not null)
            rRequest.AddParameter("end", request.To.ToString());

        if (request.Count is not null)
            rRequest.AddParameter("limit", request.Count.ToString());

        var rawData = await rClient.GetAsync<IEnumerable<decimal[]>>(rRequest, cancellationToken)
            ?? [];

        var result = new List<Candle>();

        foreach (var item in rawData)
        {
            result.Add(new Candle
            {
                Pair = request.Pair,
                OpenTime = DateTimeOffset.FromUnixTimeMilliseconds(Convert.ToInt64(item[0])),
                OpenPrice = item[1],
                ClosePrice = item[2],
                HighPrice = item[3],
                LowPrice = item[4],
                TotalVolume = item[5],
                TotalPrice = (item[1] + item[2] + item[3] + item[4]) / 4
            });
        }

        return result;
    }
}