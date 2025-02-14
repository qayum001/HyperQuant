using ConnectorTest;
using HyperQuant.Commands;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Windows.Input;
using TestHQ;

namespace HyperQuant;

internal class MainWindowViewModel
{
    private readonly ITestConnector _connector;
    #region Trades   
    public ObservableCollection<Trade> Trades { get; } = [];

    public ICommand FetchTradesCommand { get; }

    public event PropertyChangedEventHandler? PropertyChanged;
    private string _tradePair = "BTCUSD";
    public string TradePair
    {
        get => _tradePair;
        set
        {
            _tradePair = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(TradePair)));
        }
    }

    private int _maxTrades = 100;
    public int MaxTrades
    {
        get => _maxTrades;
        set
        {
            if (value < 1) return;
            _maxTrades = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(MaxTrades)));
        }
    }

    public MainWindowViewModel(ITestConnector connector)
    {
        _connector = connector; 
        FetchTradesCommand = new RelayCommand(FetchTradesAsync);
        FetchCandlesCommand = new RelayCommand(FetchCandlesAsync);
    }

    private async Task FetchTradesAsync()
    {
        var trades = await _connector.GetNewTradesAsync(TradePair, MaxTrades);
        Trades.Clear();

        foreach (var item in trades)
            Trades.Add(item);
    }
    #endregion

    #region Candles
    public ObservableCollection<Candle> Candles { get; } = new ObservableCollection<Candle>();

    private string _candlePair = "BTCUSD";
    public string CandlePair
    {
        get => _candlePair;
        set
        {
            _candlePair = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(CandlePair)));
        } 
    }

    private int _periodInSec = 300;
    public int PeriodInSec
    {
        get => _periodInSec;
        set
        {
            _periodInSec = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(PeriodInSec)));
        }
    }

    private DateTimeOffset _from = new(DateTime.Now - TimeSpan.FromMinutes(10), TimeZoneInfo.Local.GetUtcOffset(DateTime.Now));
    public DateTimeOffset From
    {
        get => _from;
        set
        {
            _from = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(From)));
        }
    }

    private DateTimeOffset _to = new(DateTime.Now, TimeZoneInfo.Local.GetUtcOffset(DateTime.Now));
    public DateTimeOffset To
    {
        get => _to;
        set
        {
            _to = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(To)));
        }
    }

    private long? _count = 100;
    public long? Count
    {
        get => _count;
        set
        {
            _count = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Count)));
        }
    }

    public ICommand FetchCandlesCommand { get; }
    private async Task FetchCandlesAsync()
    {
        var cangles = await _connector.GetCandleSeriesAsync(CandlePair, PeriodInSec, From, To, Count);
        Candles.Clear();

        foreach (var c in cangles)
            Candles.Add(c);
    }
    #endregion
}
