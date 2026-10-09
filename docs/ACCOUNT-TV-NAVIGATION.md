# Account navigation fix

The reported TV screen put editable source credentials directly below the sync entry button. Entering the username activated the IME, and no explicit field/IME navigation led to password or the sync entry. PR48 also added optional source fields to the Firebase dialog despite the separate provider form.

## Changes

- The TV account page exposes two separately named entry buttons, Cinema iQ sync and Cinemana. Initial focus goes to the sync button, with explicit Up/Down between buttons and restored focus after closing a dialog.
- Source sign-in is in its own dialog. Its username/password and Next/Done routes are explicit. Source login/logout and token storage are unchanged.
- Firebase dialog contains only sync credentials and actions. Removed optional source fields and simultaneous source sign-in; phone/TV callers no longer pass source login callbacks. The mobile source section is clearly labelled Cinemana.
- AccountTextField handles TV Up/Down transitions. TV focus alone does not open the soft keyboard; Select opens it. IME Next targets the next field, Done hides the keyboard and selects the action. Touch retains normal keyboard behavior.
- Empty cloud form buttons remain reachable and validate input when pressed. Verified/unverified account actions have explicit vertical focus routes.
- No local preference keys, Firebase sync logic, versions, APKs or public releases changed.

## Validation

Final Debug/release Kotlin compilation and all 168 existing JVM tests passed (zero failures/errors). JVM tests do not exercise TV focus or IME. No APK was built and no real-TV/keyboard or live authentication test was performed. GitHub also checks tests/R8 for this PR.
