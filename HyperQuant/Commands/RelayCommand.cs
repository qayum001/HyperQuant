using System.Windows.Input;

namespace HyperQuant.Commands;

internal class RelayCommand : ICommand
{
    private readonly Func<Task> _execute;
    private readonly Func<bool> _canExecute;

    public RelayCommand(Func<Task> execute, Func<bool> canExecute = null)
    {
        _execute = execute;
        _canExecute = canExecute;
    }

    public event EventHandler CanExecuteChanged;
    public bool CanExecute(object parameter) => _canExecute == null || _canExecute();
    public async void Execute(object parameter) => await _execute();
}