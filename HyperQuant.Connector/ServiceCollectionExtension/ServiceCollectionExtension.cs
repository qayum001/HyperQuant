using ConnectorTest;
using HyperQuant.Connector.Clients;
using HyperQuant.Connector.HQ_Test_Data;
using Microsoft.Extensions.DependencyInjection;
using System.Reflection;

namespace HyperQuant.Connector.ServiceCollectionExtension;

public static class ServiceCollectionExtension
{
    public static void AddHyperQuantConnector(this IServiceCollection services)
    {
        services.AddMediatR(cfg =>
        {
            cfg.RegisterServicesFromAssembly(Assembly.GetExecutingAssembly());
        });

        services.AddHttpClient("Bitfinix", e =>
        {
            e.BaseAddress = new("https://api-pub.bitfinex.com/v2/");
        });

        services.AddTransient<IRestConnectorClient, RestConnectorClient>();
        services.AddTransient<ITestConnector, TestConnector>();
    }
}