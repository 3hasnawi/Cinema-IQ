using CinemaIQ.Core;
using Microsoft.Win32;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
namespace CinemaIQ.Windows;

public sealed partial class MainWindow
{
    private void OpenSettings()
    {
        Begin("settings"); var settings = Store.State.Settings;
        var source = new ComboBox { ItemsSource = new[] { "سينمانا", "فودو", "دورلي", "سينمانا وفودو معًا" }, SelectedIndex = (int)settings.Source, MinWidth = 260 };
        var language = new ComboBox { ItemsSource = new[] { "الإنجليزية", "اللغة الأصلية" }, SelectedIndex = settings.EnglishTitles ? 0 : 1 };
        var quality = new ComboBox { ItemsSource = new[] { "تلقائي", "أعلى جودة متاحة", "حد أقصى 1080p", "حد أقصى 720p" }, SelectedIndex = settings.Quality switch { "highest" => 1, "1080" => 2, "720" => 3, _ => 0 } };
        var subtitleSize = new Slider { Minimum = 14, Maximum = 48, Value = settings.SubtitleSize, TickFrequency = 2, IsSnapToTickEnabled = true, Margin = new Thickness(20), Width = 360 };
        var background = new ComboBox { ItemsSource = new[] { "بدون خلفية", "خلفية خفيفة", "خلفية متوسطة", "خلفية داكنة" }, SelectedIndex = Math.Clamp(settings.SubtitleBackground / 64, 0, 3) };
        var hardware = new CheckBox { Content = "تسريع الفيديو باستخدام بطاقة الرسوميات", IsChecked = settings.HardwareDecoding };
        var reduced = new CheckBox { Content = "تقليل الحركة والانتقالات", IsChecked = settings.ReducedMotion };
        var content = Ui.Stack(Ui.Text("الإعدادات", 36), Ui.Card(Ui.Stack(Ui.Text("مصادر المحتوى", 24), source, Ui.Text("اختر المصدر الذي تدعمه شبكتك. لا يوفّر التطبيق اشتراكًا أو يتجاوز صلاحيات المصدر.", 15, Ui.Muted))), Ui.Card(Ui.Stack(Ui.Text("العرض والتشغيل", 24), Ui.Text("لغة أسماء الأعمال"), language, Ui.Text("جودة التشغيل الافتراضية"), quality, hardware, reduced)), Ui.Card(Ui.Stack(Ui.Text("الترجمة", 24), Ui.Text("الحجم الافتراضي"), subtitleSize, Ui.Text("الخلفية الافتراضية"), background, Ui.Text("تُطبّق إعدادات تنسيق الترجمة عند فتح المشغّل التالي. اختيار المسار متاح داخل المشغّل.", 15, Ui.Muted))), Ui.Row(Ui.Button("حفظ الإعدادات", () => { settings.Source = (SourceKind)source.SelectedIndex; settings.EnglishTitles = language.SelectedIndex == 0; settings.Quality = new[] { "auto", "highest", "1080", "720" }[quality.SelectedIndex]; settings.SubtitleSize = (int)subtitleSize.Value; settings.SubtitleBackground = background.SelectedIndex * 64; settings.HardwareDecoding = hardware.IsChecked == true; settings.ReducedMotion = reduced.IsChecked == true; Store.Save(); status.Text = "حُفظت الإعدادات."; }, true), Ui.Button("حساب المزامنة", OpenAccount)), Ui.Card(Ui.Stack(Ui.Text("Cinema iQ Windows · 0.1.0", 22), Ui.Text("الأسهم وEnter للتنقل · F11 لملء الشاشة · Esc للرجوع\nداخل المشغّل: Space تشغيل/إيقاف · الأسهم للتقديم أو تبديل القنوات · M للصوت", 16, Ui.Muted), Ui.Button("تراخيص المكتبات", () => System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(Path.Combine(AppContext.BaseDirectory, "THIRD-PARTY-NOTICES.txt")) { UseShellExecute = true })))));
        content.Margin = new Thickness(24); page.Content = new ScrollViewer { Content = content };
    }
    private void OpenProfiles()
    {
        Begin("profile"); var panel = Ui.Stack(Ui.Text("من يشاهد الآن؟", 36), Ui.Text(Services.Cloud.Session?.Email ?? "الملفات الشخصية المحلية", 18, Ui.Muted));
        foreach (var p in Store.State.Profiles)
        {
            var color = Color.FromArgb((byte)(p.Color >> 24), (byte)(p.Color >> 16), (byte)(p.Color >> 8), (byte)p.Color);
            var avatar = Ui.Text(p.Name.Length > 0 ? p.Name[..1] : "●", 34); avatar.TextAlignment = TextAlignment.Center;
            var circle = new Border { Width = 72, Height = 72, CornerRadius = new CornerRadius(36), Background = new SolidColorBrush(color), Child = avatar };
            if (Path.IsPathFullyQualified(p.Avatar) && p.Avatar.StartsWith(Path.Combine(Services.Folder, "Avatars") + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase) && File.Exists(p.Avatar)) try { circle.Child = new Image { Source = new BitmapImage(new Uri(p.Avatar)), Stretch = Stretch.UniformToFill }; circle.Clip = new EllipseGeometry(new Point(36, 36), 36, 36); } catch { }
            panel.Children.Add(Ui.Card(Ui.Row(circle, Ui.AsyncButton(p.Name + (p.Id == Store.State.ActiveProfile ? "  ✓" : ""), async () => { Store.State.ActiveProfile = p.Id; Store.Save(); await OpenHome(); }, ShowError), Ui.Button("تعديل", () => EditProfile(p)), Ui.Button("حذف", () => { if (p.Id == "main") { status.Text = "لا يمكن حذف الملف الرئيسي."; return; } if (MessageBox.Show(this, "حذف الملف الشخصي؟", "Cinema iQ", MessageBoxButton.OKCancel) != MessageBoxResult.OK) return; Store.State.Profiles.RemoveAll(x => x.Id == p.Id); Store.State.PendingProfiles.Remove(p.Id); Store.State.DeletedProfiles.Add(p.Id); if (Store.State.ActiveProfile == p.Id) Store.State.ActiveProfile = "main"; Store.Save(); OpenProfiles(); }))));
        }
        panel.Children.Add(Ui.Row(Ui.Button("إضافة ملف شخصي", () => EditProfile(null)), Ui.Button("حساب المزامنة", OpenAccount))); panel.Margin = new Thickness(35); page.Content = new ScrollViewer { Content = panel };
    }
    private void EditProfile(Profile? old)
    {
        if (old == null && Store.State.Profiles.Count >= 6) { status.Text = "الحد الأقصى ستة ملفات شخصية."; return; }
        var name = Ui.Input("اسم الملف", old?.Name ?? ""); name.MaxLength = 16; name.FlowDirection = FlowDirection.RightToLeft;
        var avatar = new ComboBox { ItemsSource = Enumerable.Range(1, 6).Select(i => "صورة " + i).ToArray(), SelectedIndex = int.TryParse(old?.Avatar.Replace("avatar-", ""), out var n) ? Math.Clamp(n, 0, 5) : 0 };
        var imported = old?.Avatar ?? ""; var message = Ui.Text("", 14, Ui.Muted); Window? dialog = null;
        var import = Ui.Button("استيراد صورة", () => { var picker = new OpenFileDialog { Filter = "الصور|*.png;*.jpg;*.jpeg;*.bmp", Multiselect = false }; if (picker.ShowDialog(this) != true) return; try { if (new FileInfo(picker.FileName).Length > 6 * 1024 * 1024) throw new CinemaException("اختر صورة أصغر من 6 ميغابايت."); var bitmap = new BitmapImage(); bitmap.BeginInit(); bitmap.UriSource = new Uri(picker.FileName); bitmap.DecodePixelWidth = 512; bitmap.CacheOption = BitmapCacheOption.OnLoad; bitmap.EndInit(); var folder = Path.Combine(Services.Folder, "Avatars"); Directory.CreateDirectory(folder); imported = Path.Combine(folder, Guid.NewGuid().ToString("N") + ".png"); using var output = File.Create(imported); var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap)); encoder.Save(output); message.Text = "حُفظت الصورة على هذا الجهاز."; } catch (Exception e) { message.Text = Ui.Message(e); } });
        avatar.SelectionChanged += (_, _) => imported = "";
        var save = Ui.Button("حفظ", () => { if (string.IsNullOrWhiteSpace(name.Text)) { message.Text = "أدخل اسم الملف."; return; } var colors = new long[] { 4294937131, 4294925434, 4282242752, 4287597567, 4294940987, 4283270143 }; var profile = new Profile(old?.Id ?? "p" + Guid.NewGuid().ToString("N"), name.Text.Trim(), old?.Color ?? colors[avatar.SelectedIndex], imported.Length > 0 ? imported : "avatar-" + avatar.SelectedIndex); Store.State.Profiles.RemoveAll(p => p.Id == profile.Id); Store.State.Profiles.Add(profile); Store.State.PendingProfiles[profile.Id] = profile; Store.Save(); dialog!.Close(); OpenProfiles(); }, true);
        dialog = Ui.Dialog(this, old == null ? "ملف شخصي جديد" : "تعديل الملف", Ui.Stack(name, avatar, import, message, save)); dialog.ShowDialog();
    }
    private void OpenAccount()
    {
        var email = Ui.Input("البريد الإلكتروني", Services.Cloud.Session?.Email ?? ""); var password = new PasswordBox { MinWidth = 280, MinHeight = 44 }; var message = Ui.Text(Services.Cloud.Session == null ? "سجّل بحساب Cinema iQ لمزامنة قائمتك وتقدم المشاهدة." : "الحساب الحالي: " + Services.Cloud.Session.Email, 17, Ui.Muted);
        async Task Login(bool create) { await Services.SignIn(email.Text.Trim(), password.Password, create, request.Token); password.Clear(); if (Services.Cloud.Session?.Verified == true) { await Services.Sync(request.Token); message.Text = "تم تسجيل الدخول والمزامنة."; } else message.Text = "افتح رابط تأكيد البريد، ثم اضغط تحققت من البريد."; }
        var content = Ui.Stack(Ui.Text("البريد الإلكتروني"), email, Ui.Text("كلمة المرور"), password, message, Ui.Row(Ui.AsyncButton("تسجيل الدخول", () => Login(false), e => message.Text = e, true), Ui.AsyncButton("إنشاء حساب", () => Login(true), e => message.Text = e)), Ui.Row(Ui.AsyncButton("تحققت من البريد / مزامنة الآن", async () => { await Services.Sync(request.Token); message.Text = Services.Cloud.Session?.Verified == true ? "تمت المزامنة." : "لم يتم تأكيد البريد بعد."; }, e => message.Text = e), Ui.AsyncButton("نسيت كلمة المرور", async () => { await Services.Cloud.ResetPassword(email.Text.Trim(), request.Token); message.Text = "أُرسلت رسالة تغيير كلمة المرور."; }, e => message.Text = e)), Ui.AsyncButton("تسجيل الخروج", async () => { await Services.SignOut(request.Token); message.Text = "تم تسجيل الخروج. بيانات كل حساب محفوظة منفصلة على الجهاز."; }, e => message.Text = e));
        Ui.Dialog(this, "حساب المزامنة", content, 760).ShowDialog(); OpenProfiles();
    }
    private async Task OpenIptv(string? selectedId = null)
    {
        var token = Begin("iptv"); var top = Ui.Stack(Ui.Text("IPTV", 36), Ui.Row(Ui.Button("إضافة مصدر", AddIptvSource), Ui.AsyncButton("تحديث", () => OpenIptv(), ShowError)));
        var chosen = Services.IptvSources.FirstOrDefault(s => s.Id == (selectedId ?? Store.State.IptvSourceId)) ?? Services.IptvSources.FirstOrDefault();
        if (chosen == null) { top.Children.Add(Ui.Card(Ui.Stack(Ui.Text("أضف رابط M3U أو اشتراك Xtream", 28), Ui.Text("بيانات الاشتراك تبقى محمية بحساب Windows على هذا الجهاز، ولا تُرسل إلى حساب المزامنة.", 17, Ui.Muted)))); page.Content = top; return; }
        var picker = new ComboBox { ItemsSource = Services.IptvSources, SelectedItem = chosen, MinWidth = 240 };
        picker.SelectionChanged += async (_, _) => { if (picker.SelectedItem is IptvSource s && s.Id != chosen.Id) { try { await OpenIptv(s.Id); } catch (Exception e) { ShowError(Ui.Message(e)); } } };
        top.Children.Add(Ui.Row(picker, Ui.Button("حذف المصدر", () => { if (MessageBox.Show(this, "حذف المصدر من هذا الجهاز؟", "Cinema iQ", MessageBoxButton.OKCancel) == MessageBoxResult.OK) { Services.IptvSources.Remove(chosen); Services.SaveSources(); _ = Open("iptv"); } })));
        var note = Ui.Text("جارٍ تحميل القنوات…", 17, Ui.Muted); top.Children.Add(note); page.Content = top;
        Store.State.IptvSourceId = chosen.Id; Store.Save(); var channels = await new IptvClient(Services.Network).Load(chosen, token); token.ThrowIfCancellationRequested();
        var group = new ComboBox { ItemsSource = new[] { "كل القنوات" }.Concat(channels.Select(c => c.Group).Distinct().Order()).ToArray(), SelectedIndex = 0, MinWidth = 220 }; var query = Ui.Input("ابحث عن قناة");
        var list = new ListBox { Background = Ui.Ink, Foreground = Brushes.White, BorderThickness = new Thickness(0), DisplayMemberPath = "Name", FontSize = 21, FlowDirection = FlowDirection.RightToLeft, Margin = new Thickness(8) };
        VirtualizingPanel.SetIsVirtualizing(list, true); VirtualizingPanel.SetVirtualizationMode(list, VirtualizationMode.Recycling);
        var style = new Style(typeof(ListBoxItem)); style.Setters.Add(new Setter(Control.PaddingProperty, new Thickness(14))); style.Setters.Add(new Setter(Control.MarginProperty, new Thickness(4))); style.Setters.Add(new Setter(Control.BackgroundProperty, Ui.Panel)); style.Setters.Add(new Setter(Control.ForegroundProperty, Brushes.White)); var active = new Trigger { Property = ListBoxItem.IsSelectedProperty, Value = true }; active.Setters.Add(new Setter(Control.BackgroundProperty, Ui.Accent)); active.Setters.Add(new Setter(Control.ForegroundProperty, Ui.Ink)); style.Triggers.Add(active); list.ItemContainerStyle = style;
        IReadOnlyList<Channel> visible = channels;
        void Filter() { visible = channels.Where(c => (group.SelectedIndex == 0 || c.Group == group.SelectedItem?.ToString()) && c.Name.Contains(query.Text.Trim(), StringComparison.OrdinalIgnoreCase)).ToArray(); list.ItemsSource = visible; list.SelectedIndex = visible.Count > 0 ? 0 : -1; note.Text = visible.Count + " قناة"; }
        void PlaySelected() { if (list.SelectedItem is Channel c) new PlayerWindow(this, Services, c, channels, group.SelectedIndex == 0 ? null : group.SelectedItem?.ToString()).ShowDialog(); }
        query.TextChanged += (_, _) => Filter(); group.SelectionChanged += (_, _) => Filter(); list.MouseDoubleClick += (_, _) => PlaySelected(); list.KeyDown += (_, e) => { if (e.Key == System.Windows.Input.Key.Enter) { PlaySelected(); e.Handled = true; } };
        top.Children.Add(Ui.Row(query, group, Ui.Button("مشاهدة", PlaySelected, true))); Filter(); var grid = new Grid(); grid.RowDefinitions.Add(new() { Height = GridLength.Auto }); grid.RowDefinitions.Add(new()); grid.Children.Add(top); Grid.SetRow(list, 1); grid.Children.Add(list); page.Content = grid; list.Focus();
    }
    private void AddIptvSource()
    {
        if (Services.IptvSources.Count >= 20) { status.Text = "الحد الأقصى عشرون مصدرًا."; return; }
        var name = Ui.Input("اسم المصدر"); var address = Ui.Input("رابط القائمة أو الخادم"); var user = Ui.Input("اسم المستخدم"); var pass = new PasswordBox { MinHeight = 40 }; var kind = new ComboBox { ItemsSource = new[] { "رابط M3U", "اشتراك Xtream" }, SelectedIndex = 0 }; var output = new ComboBox { ItemsSource = new[] { "HLS (m3u8)", "MPEG-TS" }, SelectedIndex = 0 }; var http = new CheckBox { Content = "السماح باتصال HTTP لهذا المصدر", ToolTip = "اتصال HTTP غير مشفّر. يفضّل HTTPS." }; var message = Ui.Text("", 15, Ui.Muted); Window? dialog = null;
        var save = Ui.AsyncButton("حفظ وتحميل", async () => { var source = new IptvSource { Name = name.Text.Trim(), Address = address.Text.Trim(), Kind = kind.SelectedIndex == 0 ? IptvKind.M3u : IptvKind.Xtream, Username = user.Text.Trim(), Password = pass.Password, AllowHttp = http.IsChecked == true, Output = output.SelectedIndex == 0 ? "m3u8" : "ts" }; source.Validate(); address.Text = source.Address; message.Text = "جارٍ التحقق…"; await new IptvClient(Services.Network).Load(source, request.Token); Services.IptvSources.Add(source); Services.SaveSources(); dialog!.Close(); await OpenIptv(source.Id); }, e => message.Text = e, true);
        dialog = Ui.Dialog(this, "إضافة مصدر IPTV", Ui.Stack(Ui.Text("اسم المصدر"), name, kind, Ui.Text("عنوان الخادم أو رابط القائمة"), address, Ui.Text("اسم المستخدم (Xtream)"), user, Ui.Text("كلمة المرور (Xtream)"), pass, output, http, message, save), 690); dialog.ShowDialog();
    }
}
