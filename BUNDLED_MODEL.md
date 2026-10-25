# Personal TV build: bundled Russian recognition model

This fork bundles `vosk-model-small-ru-0.22` supplied locally by the owner,
from the Vosk small Russian model distribution:
https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip

Model information and licensing are published at https://alphacephei.com/vosk/models.
The model's README is retained inside the archive. No recognition library is added.

For Russian, pressing the existing model download button copies the bundled archive
instead of making a network request. The existing extraction and model-loading
code is reused. Other languages retain their existing download behavior.

This does not enable the wake word, grant microphone permission, or change the
default assistant. The debug build uses the upstream branch-based application ID
suffix (on `main`: `org.stypox.dicio.main`) and can coexist with official Dicio.
