# Personal TV build: Google speech input

The bundled Vosk model and Vosk/JNA dependencies have been removed.
Speech input uses the existing external-popup adapter, pinned to
`com.google.android.katniss`, returning recognized text to Dicio skills.

Legacy stored Vosk input preferences resolve to this adapter. No new library is added.

This requires Google app for Android TV to be enabled. It does not require the
Google TV launcher. Microphone routing and result delivery require device testing.

This does not enable the wake word, grant microphone permission, or change the
default assistant. The debug build uses the upstream branch-based application ID
suffix (on `main`: `org.stypox.dicio.main`) and can coexist with official Dicio.
