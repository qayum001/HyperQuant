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
        FetchTradesCommand = new RelayCommand(async () => await FetchTradesAsync());
    }

    private async Task FetchTradesAsync()
    {
        var trades = await _connector.GetNewTradesAsync(TradePair, MaxTrades);
        Trades.Clear();

        foreach (var item in trades)
            Trades.Add(item);
    }
}
