# Personal TV build: Google speech input

The bundled Vosk model and Vosk/JNA dependencies have been removed.
Speech input calls Google's `KatnissRecognitionService` directly through
`SpeechRecognizer`, returning recognized text to Dicio skills. Google does not
interpret or execute Dicio commands. The external Google popup is not used.

Legacy stored Vosk input preferences resolve to this adapter. No new library is added.

This requires Google app for Android TV to be enabled. It does not require the
Google TV launcher. Microphone routing and result delivery require device testing.

The recognizer client is retained between explicit requests, but recording starts
only on request. Partial results update the UI; only final results execute skills.
Timeout and owner destruction release the client. No-match errors are displayed
instead of silently clearing the interaction. There are no automatic retries.

On the tested TCL firmware, remote audio activation depends on Android TV Remote's
selected audio device. Physical OK tests opened the BLE stream and Dicio launched
YouTube itself; mouse-only tests also reached READY without opening the BLE stream.
READY is therefore not proof that remote audio is available. Retaining the client
does not by itself solve this system-side device-selection issue.

This does not enable the wake word, grant microphone permission, or change the
default assistant. The debug build uses the upstream branch-based application ID
suffix (on `main`: `org.stypox.dicio.main`) and can coexist with official Dicio.
