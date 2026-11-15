using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Automation;
namespace CinemaIQ.Windows;

public static class Ui
{
    public static readonly Brush Ink = new SolidColorBrush(Color.FromRgb(24, 29, 37));
    public static readonly Brush Panel = new SolidColorBrush(Color.FromRgb(31, 37, 46));
    public static readonly Brush Accent = new SolidColorBrush(Color.FromRgb(242, 178, 51));
    public static readonly Brush Muted = new SolidColorBrush(Color.FromRgb(163, 175, 194));
    public static TextBlock Text(string text, double size = 18, Brush? brush = null) => new() { Text = text, FontSize = size, Foreground = brush ?? Brushes.White, FlowDirection = FlowDirection.RightToLeft, TextAlignment = TextAlignment.Right, TextWrapping = TextWrapping.Wrap, VerticalAlignment = VerticalAlignment.Center };
    public static Button Button(string title, Action action, bool primary = false)
    {
        var button = new Button { Content = Text(title, 17, primary ? Ink : Brushes.White), Background = primary ? Accent : new SolidColorBrush(Color.FromArgb(180, 39, 46, 58)), MinHeight = 42 };
        AutomationProperties.SetName(button, title); button.Click += (_, _) => action(); return button;
    }
    public static Button AsyncButton(string title, Func<Task> action, Action<string>? error = null, bool primary = false)
    {
        var button = Button(title, () => { }, primary);
        button.Click += async (_, _) => { button.IsEnabled = false; try { await action(); } catch (OperationCanceledException) { } catch (Exception ex) { error?.Invoke(Message(ex)); } finally { button.IsEnabled = true; } };
        return button;
    }
    public static string Message(Exception ex) => ex is Core.CinemaException ? ex.Message : ex is System.Net.Http.HttpRequestException ? "تعذّر الاتصال. تحقق من الشبكة وأعد المحاولة." : "تعذّر إكمال العملية. أعد المحاولة.";
    public static StackPanel Stack(params UIElement[] children) { var p = new StackPanel(); foreach (var child in children) p.Children.Add(child); return p; }
    public static StackPanel Row(params UIElement[] children) { var p = Stack(children); p.Orientation = Orientation.Horizontal; p.FlowDirection = FlowDirection.RightToLeft; return p; }
    public static Border Card(UIElement child, double padding = 20) => new() { Child = child, Background = new SolidColorBrush(Color.FromArgb(220, 31, 37, 46)), CornerRadius = new CornerRadius(24), BorderBrush = new SolidColorBrush(Color.FromArgb(45, 255, 255, 255)), BorderThickness = new Thickness(1), Padding = new Thickness(padding), Margin = new Thickness(8) };
    public static TextBox Input(string name, string value = "") { var box = new TextBox { Text = value, MinWidth = 240, FlowDirection = FlowDirection.LeftToRight }; AutomationProperties.SetName(box, name); box.ToolTip = name; return box; }
    public static Window Dialog(Window owner, string title, UIElement content, double width = 600)
    {
        var window = new Window { Owner = owner, Title = title, Width = width, SizeToContent = SizeToContent.Height, MaxHeight = Math.Max(500, owner.ActualHeight - 60), WindowStartupLocation = WindowStartupLocation.CenterOwner, ResizeMode = ResizeMode.NoResize, ShowInTaskbar = false };
        var close = Button("إغلاق", () => window.Close());
        window.Content = new ScrollViewer { Content = Stack(Card(Stack(Text(title, 26), content, close))), HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
        window.PreviewKeyDown += (_, e) => { if (e.Key == System.Windows.Input.Key.Escape) { window.Close(); e.Handled = true; } };
        return window;
    }
    public static void OpenBrowser(Uri uri) => System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true });
}
