# iPhone parity with the approved Apple TV changes

Base: main `cc6b57c`, Apple TV 0.4.6. This task reviews the existing iPhone and
Apple TV implementations and ports applicable improvements to the touch UI.

Reserved scope: iPhone HomeView/DetailView and a dedicated artwork component;
iPhone IPTV views/panels and their input tests; shared PlaybackController startup
and existing movie controls, with focused playback tests. Shared Core policies
are reused rather than duplicated. Android is outside this task.

Priorities: preserve library and preference keys, distinguish movie and live-TV
controls, keep Arabic/RTL and adaptive iPad behavior, and validate keyboard
Next/Done, dismissal and return focus for any modified input flow.

Apple TV Top Shelf and remote-only gestures have no direct iPhone equivalent.
Automatic hero trailers still require the previously documented provider access;
this task does not represent posters as working trailer previews.

Validation and the exact final changes will be recorded before delivery. No
version bump, IPA/APK export, merge or publication is included in this request.
