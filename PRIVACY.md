# Privacy notes for the personal TV fork

This fork is not offline-only. The following describes the app's implementation, not a guarantee about third-party services or every Android device.

## Voice and external services

When a TV voice session is invoked and microphone permission is available, Dicio requests one-shot recognition from Google's explicitly selected Katniss recognition service. Google receives the audio needed for recognition; whether it is processed locally or remotely depends on Google and the device. Dicio receives recognized text. Consult Google's policies for its handling of voice data. Do not assume recognition works without a network connection.

If RouterAI is enabled, the recognized or entered request, model ID, a catalog of eligible installed app labels and IDs, and available media-session labels, states and supported operations are sent to RouterAI's chat completions endpoint. The API key is sent for authentication. Media titles and media IDs used to verify player feedback remain local in this implementation. Consult RouterAI's policies for its handling of submitted data. The model's decision is validated locally, but model answers and actions can still be wrong.

Android text-to-speech handles spoken responses during a session. Its processing and any network use depend on the chosen system engine and its provider. The inherited skills used when RouterAI is disabled may also contact external services and send information needed for a request, such as a search query or location. Connectivity and service availability vary; disabling internet access is not guaranteed to leave the assistant functioning normally.

## Permissions and local data

`MediaAccessService` uses Android notification-listener access to query active media sessions. Its implementation has no notification callbacks and does not read notification contents, but the system permission is broad notification access, not a media-only permission. Review this access in Android settings before granting it; media controls may not work without access. The app also requests permissions for features such as microphone input and package discovery.

Preferences and other app data are stored locally. Requests and answers can appear in the app's interaction display; diagnostic logs may contain operational information or errors, so do not assume logs contain no sensitive information. RouterAI settings are stored outside backup; the API key is encrypted using an Android Keystore key. This is not a guarantee against a compromised device or service.

You can reset RouterAI settings in the app and clear app storage or uninstall the app to remove its local data. This does not delete data already received or retained by Google, RouterAI, a TTS provider or other external services; consult their policies and controls.
