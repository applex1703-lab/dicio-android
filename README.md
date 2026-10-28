# Dicio assistant

This is a personal Android TV fork of the free and open source Dicio voice assistant. It uses Google speech recognition and optionally RouterAI; it is not an offline-only assistant. Behavior and availability depend on the TV, installed services, permissions, network and configuration. See [PRIVACY.md](PRIVACY.md) for data handling and limitations.

<p align="center">
    <img width="80" alt="Dicio logo" src="./app/src/main/res/mipmap-xxxhdpi/ic_launcher.png">
    &emsp;
    <a href="https://f-droid.org/packages/org.stypox.dicio">
        <img width="206" alt="Get it on F-Droid" src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png">
    </a>
    <a href="https://github.com/Stypox/dicio-android/releases">
        <img width="206" alt="Get it on GitHub" src="./meta/get-it-on-github.png">
    </a>
    <a href="https://github.com/DicioTeam/dicio-android/issues/464">
        <img width="206" alt="Get it on Play Store" src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png">
    </a>
</p>

The badges and screenshots below refer to upstream Dicio, not releases or screenshots of this personal TV fork.

## Screenshots

[<img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/0.png" width=200>](./fastlane/metadata/android/en-US/images/phoneScreenshots/0.png)
[<img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width=200>](./fastlane/metadata/android/en-US/images/phoneScreenshots/1.png)
[<img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width=200>](./fastlane/metadata/android/en-US/images/phoneScreenshots/2.png)
[<img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width=200>](./fastlane/metadata/android/en-US/images/phoneScreenshots/3.png)
[<img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width=200>](./fastlane/metadata/android/en-US/images/phoneScreenshots/4.png)
[<img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" width=200>](./fastlane/metadata/android/en-US/images/phoneScreenshots/4.png)

## Skills

With RouterAI disabled, the inherited skill matcher handles requests. The upstream skills include the following; availability depends on the device, configuration, permissions and external services:
- **search**: looks up information on **DuckDuckGo** (and in the future more engines) - _Search for Dicio_
- **weather**: collects weather information from **OpenWeatherMap** - _What's the weather like?_
- **lyrics**: shows **Genius** lyrics for songs - _What's the song that goes we will we will rock you?_
- **open**: opens an app on your device - _Open NewPipe_
- **calculator**: evaluates basic calculations - _What is four thousand and two times three minus a million divided by three hundred?_
- **timer**: set, query and cancel timers - _Set a timer for five minutes_
- **current time**: query current time - _What time is it?_
- **navigation**: opens the navigation app at the requested position - _Take me to New York, fifteenth avenue_
- **jokes**: tells you a joke - _Tell me a joke_
- **media**: play, pause, previous, next song
- **translation**: translate from/to any language with **Lingva** - _How do I say Football in German?_

## TV voice input and output

The TV voice session starts a one-shot request when invoked as the system assistant, subject to microphone permission. It explicitly selects Google's `com.google.android.katniss` / `KatnissRecognitionService` and passes the recognized text back to Dicio; it does not execute Google Assistant actions. That component must be available and usable on the device. Recognition may require network access and is not guaranteed to work offline. Legacy Vosk and external-popup input selections map to Google recognition in this fork; selecting no input disables it.

There is no supported always-listening wake-word flow in this TV path. Invoke the assistant again for a new request; the skill dialogue does not automatically reopen the microphone. Android text-to-speech is used for spoken answers within the voice session; its engine and network behavior depend on the device. Closing the session cancels pending work and releases voice resources; actions already sent to Android or a player cannot necessarily be undone.

## RouterAI and TV actions

RouterAI is optional and disabled by default. To use it, enable it in the app settings and provide your own API key and model ID. Requests go to RouterAI over the network. When enabled, the app asks the model for one structured decision rather than running the inherited skill matcher; provider errors do not automatically fall back to skills.

The app validates decisions locally and only supports replies, clarification, device time/date, opening an eligible installed app or an allowed settings section, search results in YouTube or Rutube, and supported media-session commands. Search opens results rather than starting a video. Settings actions open a page rather than change settings. App, search and media actions depend on available handlers and player capabilities; media commands may be delivered without the player confirming success. These checks limit the action surface but do not guarantee correct model decisions or successful actions.

Media control uses `MediaAccessService`, a notification listener, to access active media sessions when notification access is granted. The service is implemented without notification-reading callbacks, but Android grants notification-listener access broadly, not a media-only system permission. Grant it only if you accept that scope; without it media control may be unavailable.

## Contributing

Dicio's code is **not only here**! The repository with the *compiler for sentences* language files is at [`dicio-sentences-compiler`](https://github.com/Stypox/dicio-sentences-compiler), the *number parser and formatter* is at [`dicio-numbers`](https://github.com/Stypox/dicio-numbers) and the code for evaluating matching algorithms is at [`dicio-evaluation`](https://github.com/Stypox/dicio-evaluation).

When contributing keep in mind that other people may have **needs** and **views different** than yours, so please *respect* them. For any question feel free to contact the project team at [@Stypox](https://github.com/Stypox).

### Matrix room for communication

The **#dicio** channel on *Matrix* is available to get in touch with the developers: [#dicio:matrix.org](https://matrix.to/#/#dicio:matrix.org). Some convenient Matrix clients, available both for phone and desktop, are listed at that link.

### Translating

If you want to translate Dicio to a new language, follow the **steps** listed in the documentation: https://dicio.stypox.org/translating.html

### Adding skills

If you want to add a new skill, or improve an existing one, check out the guide in the documentation: https://dicio.stypox.org/adding_skill.html
