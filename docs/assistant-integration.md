# Android default digital assistant (fork)

This fork adds a button/gesture-invoked Android assistant entry to OpenMinis.
It is not an official OpenMinis release. Upstream baseline: `4ef29002e88db1e20e462ec2ff46916e8a7dcb45` (1.13).

## User behavior

1. Configure an agent/model and voice input in Minis as usual.
2. In Minis Settings, open **Default digital assistant** and select Minis in the Android consent/settings UI. Android also lists Minis under Apps → Default apps → Digital assistant app.
3. On an unlocked phone, invoke the system assistant button/gesture. On Pixel, configure press-and-hold power to use the digital assistant if necessary; other vendors may reserve or remap this gesture.
4. A new chat opens and the existing microphone permission flow is used. The first nonempty final speech result is sent to the existing agent send/enqueue path. Ordinary app launches and the existing desktop voice shortcut do not auto-send.
5. Tapping the microphone returns to manual control; leaving the assistant screen or locking the phone cancels the assistant capture. A denied permission or unavailable speech provider does not bypass the normal error/setup UI.

Selecting the assistant does **not** grant OpenMinis permission to control other apps. Existing Android accessibility, integration/tool permissions and model setup remain necessary. Financial actions still need appropriate user authorization; this change does not make food ordering inherently reliable or introduce a payment bypass.

## Design

- Android supports the assistant role through an activity handling `Intent.ACTION_ASSIST`; this implementation also handles `ACTION_VOICE_ASSIST`. It uses no always-listening hotword service, no root, and no silent secure-settings writes.
- `DeepLinkHandler.parseLaunch` handles assistant actions before URI parsing. Caller extras, arbitrary prompt text, screen screenshots and assist data are not automatically forwarded to the model.
- A process-local, one-shot `AssistantLaunch` token binds microphone entry to the newly generated draft session. A session URL or a restored session alone cannot request recording; a later explicit invocation supersedes a pending earlier one.
- Cold startup creates its navigation destination once with Compose `remember`. Warm single-task intents wait until the Activity is resumed; recreating an Activity does not replay its original assistant intent.
- Pending microphone authorization is never saved across recreation. Leaving the screen/app also invalidates a request still waiting on permissions; returning from system settings requires a fresh assistant invocation. Recording starts only after the chat lifecycle is resumed, the device is unlocked and the microphone permission flow has succeeded. `AssistantVoiceTurn` rejects partial, blank, duplicate, cancelled and background results. The existing voice engine and agent submission handler are reused.
- The ordinary voice shortcut is idempotent about entering voice mode rather than toggling an already-open panel off.
- Assistant settings use `RoleManager` on Android 10+ with official settings fallbacks. Android 8/9 use the settings panel. Selection status is refreshed on return.

## Build and tests

Follow `BUILDING.md` for PRoot, its loaders and Alpine. The public tree also needs the rclone AAR:

```sh
# With JDK 17, SDK 36 and an Android NDK installed:
# Build the pinned Android submodule, not the unrelated iOS dependencies.
git submodule update --init deps/proot
./deps/build_proot.sh
./scripts/prepare_android_sandbox.sh
./deps/build_rclone_android.sh
# If the script did not install it:
install -D deps/build/rclone/rclone.aar src/android/app/libs/rclone.aar
cd src/android
./gradlew :app:testDebugUnitTest --tests 'com.openminis.app.assistant.*'
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.openminis.app.assistant
```

The original project builds ARM64 only. Do not count successful APK packaging as proof of ARM64 sandbox execution on an x86 emulator. Missing native assets must be built, not stubbed or replaced with unrelated binaries. Self-built packages use the upstream debug signing configuration; they cannot update an official package signed with a different key. Never uninstall a user's existing installation or discard its data to work around a signature mismatch.

## Acceptance matrix

The following are verification targets, **not claims of completed testing**:

- Packaged manifest resolves both assistant actions without a URI.
- Minis is offered in Android's default assistant selector; cancellation leaves the prior selection unchanged.
- System assistant invocation cold/warm/repeated opens a new voice chat once, regardless of ordinary launch-session preference.
- Returning from settings, theme recomposition, rotation/process recreation and ordinary launch do not replay recording or submit another request.
- Microphone denied, keyguard locked, backgrounding and manual cancellation do not send a request.
- One final utterance reaches the same agent path as a manual send, once; no partial/empty result is sent.
- Existing share, text/camera/voice shortcuts and normal chat submission retain their behavior.

JVM tests cover one-shot routing and voice-turn admission. Instrumented tests cover real Android intent parsing and the packaged manifest. Physical power-button dispatch, microphone/ASR quality, model credentials and task completion require real-device QA. Record actual results separately; never substitute these tests for those end-to-end checks.

## Boundaries

- First version opens the normal Minis chat UI, not a Gemini-style floating overlay.
- No wake-word/background listening or operation over the lock screen.
- No claim that becoming the default assistant makes every OEM power key configurable.
- No upstream PR: the upstream mirror explicitly does not accept pull requests.
- Source fork/branch archival is separate from binary distribution or release approval. Builds remain INTERNAL / UNPUBLISHED until explicitly approved for a stated target and audience.
