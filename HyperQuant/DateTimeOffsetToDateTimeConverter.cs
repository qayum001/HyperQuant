using System.Globalization;
using System.Windows.Data;
using System.Windows.Markup;

namespace HyperQuant
{
    internal class DateTimeOffsetToDateTimeConverter : MarkupExtension, IValueConverter
    {
        private static DateTimeOffsetToDateTimeConverter _instance;

        public override object ProvideValue(IServiceProvider serviceProvider)
        {
            return _instance ??= new DateTimeOffsetToDateTimeConverter();
        }

        public object? Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            return value is DateTimeOffset dto ? (DateTime?)dto.DateTime : null;
        }

        public object? ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        {
            return value is DateTime dt ? (DateTimeOffset?)new DateTimeOffset(dt) : null;
        }
    }
}
