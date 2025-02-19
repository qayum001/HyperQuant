using HyperQuant.Connector.Utils;
using System.Diagnostics;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using TestHQ;

namespace HyperQuant.Connector.Clients;

internal class WebSocketConnectorClient : IWebSocketConnectorClient
{
    public event Action<Trade>? NewBuyTrade;
    public event Action<Trade>? NewSellTrade;
    public event Action<Candle>? CandleSeriesProcessing;

    private ClientWebSocket _tradeWsClient;
    private ClientWebSocket _candleWsClient;
    private readonly Uri _tradeUri;
    private readonly CancellationTokenSource _cancellationTokenSource;

    private string _currentTradePair = string.Empty;
    private string _currentCandlePair = string.Empty;

    public WebSocketConnectorClient()
    {
        _tradeWsClient = new ClientWebSocket();
        _candleWsClient = new ClientWebSocket();
        _tradeUri = new Uri("wss://api-pub.bitfinex.com/ws/2");
        _cancellationTokenSource = new CancellationTokenSource();
    }

    public async void SubscribeCandles(string pair, int periodInSec, DateTimeOffset? from = null, DateTimeOffset? to = null, long? count = 0)
    {
        _currentCandlePair = pair;
        Debug.WriteLine("Subscribe to candles");
        try
        {
            if (_candleWsClient.State != WebSocketState.Open)
                await _candleWsClient.ConnectAsync(_tradeUri, _cancellationTokenSource.Token);

            var message = Encoding.UTF8.GetBytes("{" + $"\"event\":\"subscribe\",\"channel\":\"candles\",\"key\":\"trade:{TimeUtils.GetPeriodFromSeconds(periodInSec)}:t{pair}\"" + "}");

            await _candleWsClient.SendAsync(message, WebSocketMessageType.Text, true, _cancellationTokenSource.Token);

            _ = Task.Run(ReceiveCandleProcessing);
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Fail while requesting WebSocket: {ex.Message}");
        }
    }

    private async Task ReceiveCandleProcessing()
    {
        var buffer = new byte[16384];

        while (_candleWsClient.State == WebSocketState.Open)
        {
            var response = await _candleWsClient.ReceiveAsync(new ArraySegment<byte>(buffer), _cancellationTokenSource.Token);

            if (response.MessageType == WebSocketMessageType.Close)
            {
                await _candleWsClient.CloseAsync(WebSocketCloseStatus.NormalClosure, "Closing", CancellationToken.None);
                break;
            }

            string message = Encoding.UTF8.GetString(buffer, 0, response.Count);

            Debug.WriteLine(message);

            if (message[0] == '[')
            {
                using var doc = JsonDocument.Parse(message);
                var root = doc.RootElement;

                if (root[1].ValueKind == JsonValueKind.String)
                    continue;

                var candleData = root[1].EnumerateArray();

                if (candleData.ElementAt(0).ValueKind == JsonValueKind.Array)
                {
                    foreach (var item in candleData)
                    {
                        var candle = GetCandleFromJson(item.EnumerateArray());
                        CandleSeriesProcessing?.Invoke(candle);
                    }
                    continue;
                }

                CandleSeriesProcessing?.Invoke(GetCandleFromJson(candleData));
            }
        }
    }

    private Candle GetCandleFromJson(JsonElement.ArrayEnumerator candleData)
    {
        var candle = new Candle()
        {
            HighPrice = candleData.ElementAt(3).GetDecimal(),
            LowPrice = candleData.ElementAt(4).GetDecimal(),
            TotalVolume = candleData.ElementAt(5).GetDecimal(),
            Pair = _currentCandlePair,
            ClosePrice = candleData.ElementAt(2).GetDecimal(),
            OpenPrice = candleData.ElementAt(1).GetDecimal(),
            OpenTime = DateTimeOffset.FromUnixTimeMilliseconds(candleData.ElementAt(0).GetInt64()),
        };

        candle.TotalPrice = (candle.OpenPrice + candle.ClosePrice + candle.ClosePrice + candle.HighPrice) / 4;

        return candle;
    }

    //TODO: убрать maxCount, нету необходимости указывать количество трейдов
    public async void SubscribeTrades(string pair, int maxCount = 100)
    {
        _currentTradePair = pair;
        try
        {
            if (_tradeWsClient.State != WebSocketState.Open)
                await _tradeWsClient.ConnectAsync(_tradeUri, _cancellationTokenSource.Token);

            var message = Encoding.UTF8.GetBytes("{" + $"\"event\":\"subscribe\",\"channel\":\"trades\",\"symbol\":\"t{pair}\"" + "}");
            await _tradeWsClient.SendAsync(message, WebSocketMessageType.Text, true, _cancellationTokenSource.Token);

            _ = Task.Run(ReceiveTradeUpdate);

        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Fail while requesting WebSocket: {ex.Message}");
        }
    }

    private async Task ReceiveTradeUpdate()
    {
        var buffer = new byte[4096];

        while (_tradeWsClient.State == WebSocketState.Open)
        {
            var response = await _tradeWsClient.ReceiveAsync(new ArraySegment<byte>(buffer), _cancellationTokenSource.Token);

            if (response.MessageType == WebSocketMessageType.Close)
            {
                await _tradeWsClient.CloseAsync(WebSocketCloseStatus.NormalClosure, "Closing", CancellationToken.None);
                break;
            }

            string message = Encoding.UTF8.GetString(buffer, 0, response.Count);

            if (message[0] == '[')
            {
                using var doc = JsonDocument.Parse(message);
                var root = doc.RootElement;

                if (root.GetArrayLength() == 3 && root[1].ValueKind == JsonValueKind.String)
                {
                    var tradeData = root[2].EnumerateArray();
                   
                    if (tradeData.ElementAt(0).ValueKind == JsonValueKind.Number)
                    {
                        InvokeTradeUpdate(tradeData);
                        continue;
                    }
                }
                else if (root[1].ValueKind == JsonValueKind.Array)
                {
                    var trades = root[1].EnumerateArray();

                    foreach (var item in trades)
                    {
                        InvokeTradeUpdate(item.EnumerateArray());
                    }
                }
            }
        }
    }

    private void InvokeTradeUpdate(JsonElement.ArrayEnumerator tradeData)
    {
        var trade = GetTradeFromJson(tradeData);

        if (trade.Amount > 0)
            NewBuyTrade?.Invoke(trade);
        else
            NewSellTrade?.Invoke(trade);
    }

    private Trade GetTradeFromJson(JsonElement.ArrayEnumerator tradeData)
    {
        return new Trade()
        {
            Id = tradeData.ElementAt(0).GetDecimal().ToString(),
            Pair = _currentTradePair,
            Amount = tradeData.ElementAt(2).GetDecimal(),
            Time = DateTimeOffset.FromUnixTimeMilliseconds(tradeData.ElementAt(1).GetInt64()),
            Price = tradeData.ElementAt(3).GetDecimal(),
            Side = tradeData.ElementAt(2).GetDecimal() < 0 ? "Sold" : "Bought"
        };
    }
    
    public void UnsubscribeCandles(string pair)
    {
        _candleWsClient.Abort();
        _candleWsClient = new();
    }

    public void UnsubscribeTrades(string pair)
    {
        _tradeWsClient.Abort();
        _tradeWsClient = new();
    }
}