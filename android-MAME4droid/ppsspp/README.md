# PPSSPP inside Beetle Game Room

PPSSPP 1.20.4 (GPL-2.0-or-later, https://www.ppsspp.org, source:
https://github.com/hrydgard/ppsspp/releases/tag/v1.20.4) as a library module.
The game list starts `org.ppsspp.ppsspp.PpssppActivity` in the `:psp` process with
`org.ppsspp.ppsspp.Args` = `"<disc>" --pause-menu-exit`.

- `src/main/java`, `res`, `assets`: copied unchanged from the PPSSPP source tree
  (`android/src`, `android/src/main/res`, `assets`).
- `src/main/jniLibs/arm64-v8a/*.so`: built from the source tarball with the changes in
  `ppsspp-1.20.4.patch` (NDK 29.0.14206865, JDK 21, `./gradlew assembleNormalOptimized`
  in `android/`; FFmpeg libraries from the ppsspp-ffmpeg repository). They are not
  committed, like the MAME engine; copy them from
  `android/build/intermediates/stripped_native_libs/normalOptimized/.../lib/arm64-v8a/`.
- The patch keeps `--pause-menu-exit` after Android reloads the config, so «Exit» in
  the pause menu returns to the game list.
