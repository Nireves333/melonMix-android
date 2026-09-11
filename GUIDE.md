# The guide

Everything from a fresh clone to playing with all the enhancements. Skip what you
don't need.

## 1. Get the APK

Grab the latest APK from the
[releases page](https://github.com/Nireves333/melonMix-android/releases) and install
it with `adb install -r` or by sideloading. Then jump to step 2.

Or build it yourself:

You need Git, a JDK (21 or newer, the one bundled with Android Studio works), and
an Android SDK with **NDK 28.0.13004108** and **CMake 3.22.1**
(`sdkmanager "ndk;28.0.13004108" "cmake;3.22.1"`).

```
git clone --recurse-submodules https://github.com/Nireves333/melonMix-android.git
```

On Windows, clone into a short path like `C:\MelonMix`. The emulator core's native
build fails on long paths.

```
gradlew.bat :app:assembleGitHubProdDebug     (Windows)
./gradlew :app:assembleGitHubProdDebug       (Linux/macOS)
```

The first build compiles the whole emulator core and takes around 15 minutes. After
that it's about a minute. The APK ends up at
`app/build/outputs/apk/gitHubProd/debug/app-gitHub-prod-debug.apk`. Install it with
`adb install -r` or sideload it.

Note: debug builds install as package `com.nireves333.melonmix.dev`, next to the
release app (`com.nireves333.melonmix`). A signed release build is
`:app:assembleGitHubProdRelease` with a keystore set through the `MELONDS_KEYSTORE*`
entries in `local.properties`. The package name matters once, for the asset path in
step 3.

## 2. First run

1. Launch the app and point it at the folder with your `.nds` ROMs (your own dumps,
   see the README). The games show up on the select screen.
2. No BIOS or firmware files are needed. Built-in replacements are used.
3. Bind your controller: *Settings > Input*, open the key mapping, and press
   **KH layout** in the top bar. That sets up the intended layout in one tap:
   movement on the left stick, camera on the right, Lock On on R1, Switch Target on
   the triggers, command menu on the d-pad, HUD toggle on L3, map on Select. You
   can rebind anything afterwards. Camera speed has its own setting in the same
   screen (I have mine set to 1.5 for my RG505). 

You can stop here and you'll have the full single screen experience. The asset
packs below are optional extras on top.

## 3. Asset packs (HD cutscenes, music, subtitles, retranslation)

These are the same packs desktop KH Melon Mix uses. Check the
[KH Melon Mix project](https://github.com/vitor251093/KHMelonMix) for how to get
them. Once you have them, copy them onto the device into this tree:

```
Android/data/com.nireves333.melonmix/files/assets/    (add .dev for debug builds)
├── days/
│   ├── audio/<pack name>/bgm0.wav, bgm1.wav, ...
│   ├── cutscenes/cinematics/hd802.mp4, hd803.mp4, ...
│   ├── subtitles/<en|de|es|fr|it|jp>/cinematics/802.srt, ...
│   └── localization/us/en.ini
└── recoded/
    └── audio/<pack name>/bgm0.flac, ... (+ bgm.ini)
```

Some notes:

- Folder names are case sensitive and have to match exactly.
- Install as much or as little as you want. Anything missing falls back to the DS
  original, per file and per track.
- Every music pack goes in its own folder under `audio/`. Pick the active one per
  game in *Settings > Audio*. Pack changes apply when a game is launched. The
  separate music volume slider applies right away.
- Subtitles follow *Settings > System > Game language* and can be turned off in
  *Settings > Video*.

## 4. Settings worth knowing

- **Internal resolution** (*Video*): 3x holds (mostly) 60fps on the RG505.
  On weaker hardware start at 1x and work your way up.
- **Enhanced graphics** (*Video*): Turn it off and you get the
  stock dual screen DS view. (This turns off all Melon Mix features!!!)
- **Game language** (*System*): sets the language for in-game menus, the pause
  overlay and subtitles.

## 5. When something looks wrong

**Games missing from the list.** Check the ROM folder in *Settings > ROMs*, then
use *Refresh ROM list* from the menu.

**Packs not detected.** Usually the path. Check the package name
(`com.nireves333.melonmix`, plus `.dev` if you built a debug APK), a misspelled
folder, or a music pack sitting directly in `audio/` instead of its own subfolder.

**A cutscene played in DS graphics.** Only the pre-rendered cinematics have HD
videos. In-engine and dialog scenes always run on the DS engine. A missing or
misnamed `hd<id>.mp4` will fall back to the DS cutscene.

**Known bugs and planned features.** Tracked on the
[issues page](https://github.com/Nireves333/melonMix-android/issues). Nothing
serious that I know of right now. Found a new bug or want a feature? Post it in
[Discussions](https://github.com/Nireves333/melonMix-android/discussions).

**Not bugs, the desktop version of Melon Mix does this too:**

- If paused on an unskippable scene, the Continue/Skip menu will show, though the scene can't be skipped.
- Re:coded can show a duplicated sliver of the notification header
  ([KHMelonMix#476](https://github.com/vitor251093/KHMelonMix/issues/476)). I'll
  pick up their fix when it lands.

**Performance on other devices.** 64-bit with OpenGL ES 3.2 only (the JIT needs
64-bit). Internal resolution matters the most here, some devices will run at 5x fine while other can only run 2x. 

**Anything else.** Open a
[bug report](https://github.com/Nireves333/melonMix-android/discussions/new?category=bug-reports)
with your device, what you did, and a logcat capture if you can get one.
