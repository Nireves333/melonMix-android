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
entries in `local.properties`. Both packages read the same asset folder from
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
them. They go into the `MelonMix` folder at the top level of the device's
internal storage, right next to `Download`. Create it with any file manager if
it isn't there yet, then copy the packs into this tree:

```
MelonMix/assets/
├── days/
│   ├── audio/<pack name>/bgm0.wav, bgm1.wav, ...
│   ├── cutscenes/cinematics/hd802.mp4, hd803.mp4, ...
│   ├── subtitles/<en|de|es|fr|it|jp>/cinematics/802.srt, ...
│   └── localization/us/en.ini
└── recoded/
    └── audio/<pack name>/bgm0.flac, ... (+ bgm.ini)
```

The app needs the **All files access** permission to read the folder. It asks on
first launch, and the same switch is in *Settings > ROMs* along with the option
to move the folder somewhere else, like an SD card.

Coming from 1.0.0? Your packs are still in the old app folder under
`Android/data`. The app finds them on launch and offers to move them to the new
folder. Nothing is downloaded again, but the move can take a few minutes for
large packs and needs enough free space for a copy while it runs.

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
- **Single screen mode** (*Video*): On by default, everything is shown on one
  enhanced screen. Turn it off on dual screen devices (like the AYN Thor) to
  keep bottom screen content on the bottom screen. You keep the enhancements
  (HD cutscenes, music, subtitles, controls), and both screens show in your
  normal screen layout.
- **Touch controls**: no controller needed. On screen KH controls (Lock On,
  Switch Target, the command menu, shortcuts, HUD, map, a movement stick and a
  camera stick) show automatically when no controller is connected, and swap to
  the classic DS buttons when enhanced graphics is off. During a cutscene, tap
  the video to open the pause/skip menu; the menu entries are tappable too.
  Every control can be moved and resized in *Input > Layouts*.
- **KH camera sensitivity** (*Input*): camera speed for the right stick and the
  touch camera stick, from 1 to 6 in half steps. If you used this setting before
  1.1.0, the scale changed: your speed is the same, the number just shows 2
  higher than it used to, and there are two new slower steps at the bottom.
- **Touch stick deadzone** (*Input*): how far the touch sticks have to move
  before they register, as a percentage of the stick size. Applies live.
- **Game language** (*System*): sets the language for in-game menus, the pause
  overlay and subtitles.
- **Save files** (*Save Files*): saves and save states can be kept in a folder of
  your choice too. Put them next to the asset packs and they survive a reinstall
  and can be synced with tools like Syncthing.

## 5. When something looks wrong

**Glitches with an EU or JP ROM.** EU and JP ROMs get the enhancements, but with
limited support: the mod reads game memory at addresses that are only fully
confirmed for the US versions, and upstream marks some EU and JP addresses as
unverified. Expect graphical glitches, and scenes that briefly freeze and then
snap back (the app detects a stuck scene and recovers after about 3 seconds
instead of white-screening like older versions did,
[issue #10](https://github.com/Nireves333/melonMix-android/issues/10)).
Use a US ROM for the full experience. If an EU or JP ROM is unplayable, you can
still turn off enhanced graphics and play it as a plain DS game.

**Games missing from the list.** Check the ROM folder in *Settings > ROMs*, then
use *Refresh ROM list* from the menu.

**Packs not detected.** Usually the path or the permission. Check that the packs
are under `MelonMix/assets/` on internal storage (*Settings > ROMs* shows the
exact folder the app is looking at and whether it found anything), that storage
access is granted, and that no folder is misspelled. A music pack has to sit in
its own subfolder under `audio/`, not directly in it.

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
- With Single screen mode off (dual screen devices), some UI elements can break
  up around the Sora memory scenes in Days. Dual screen mode has a list of rough
  edges upstream too ([KHMelonMix#445](https://github.com/vitor251093/KHMelonMix/issues/445)).

**Performance on other devices.** 64-bit with OpenGL ES 3.2 only (the JIT needs
64-bit). Internal resolution matters the most here, some devices will run at 5x fine while other can only run 2x. 

**Anything else.** Open a
[bug report](https://github.com/Nireves333/melonMix-android/discussions/new?category=bug-reports)
with your device, what you did, and a logcat capture if you can get one.
