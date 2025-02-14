using HyperQuant.Connector.ServiceCollectionExtension;
using Microsoft.Extensions.DependencyInjection;
using System.Windows;

namespace HyperQuant;

public partial class App : Application
{
    private IServiceProvider _services;

    protected override void OnStartup(StartupEventArgs e)
    {
        var services = new ServiceCollection();

        services.AddHyperQuantConnector();
        services.AddSingleton<MainWindowViewModel>();

        _services = services.BuildServiceProvider();

        var window = new MainWindow
        {
            DataContext = _services.GetRequiredService<MainWindowViewModel>()
        };

        window.Show();

        base.OnStartup(e);
    }
}