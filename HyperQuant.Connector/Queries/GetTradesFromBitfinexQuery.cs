using MediatR;
using RestSharp;
using TestHQ;

namespace HyperQuant.Connector.Queries;

internal record GetTradesFromBitfinexQuery(string Pair, int MaxCount) : IRequest<IEnumerable<Trade>>;

internal class GetTradesFromBitfinexQueryHandler(IHttpClientFactory httpClientFactory) : IRequestHandler<GetTradesFromBitfinexQuery, IEnumerable<Trade>>
{
    public async Task<IEnumerable<Trade>> Handle(GetTradesFromBitfinexQuery request, CancellationToken cancellationToken)
    {
        var httpClient = httpClientFactory.CreateClient("Bitfinix");

        var rClient = new RestClient(httpClient);

        var rRequest = new RestRequest($"trades/t{request.Pair}/hist").AddParameter("limit", request.MaxCount);

        var rawData = await rClient.GetAsync<IEnumerable<decimal[]>>(rRequest, cancellationToken) 
            ?? [];

        var result = new List<Trade>();

        foreach (var item in rawData)
        {
            result.Add(new Trade()
            {
                Id = item[0].ToString(),
                Time = DateTimeOffset.FromUnixTimeMilliseconds(Convert.ToInt64(item[1])),
                Amount = item[2],
                Price = item[3],
                Pair = request.Pair,
                Side = item[2] < 0 ? "Sold" : "Bought"
            });
        }

        return result;
    }
}