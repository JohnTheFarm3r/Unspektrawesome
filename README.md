<div align="center">

# Unspektrawesome

**A RAW camera for Android that develops every frame like film, live in the viewfinder.**

[Download](https://github.com/JohnTheFarm3r/Unspektrawesome/releases/latest) · [spektrafilm](https://github.com/andreavolpato/spektrafilm) · [spektrafilm OFX](https://spektrafilm.114c.de/) · [License](LICENSE)

</div>

---

Most phone cameras hand you a finished picture. Somewhere between the sensor and your gallery, an algorithm has already decided how the sky should look, how warm the skin should be and how much of the shadows you get to keep.

Unspektrawesome takes the other road. It reads the raw data straight off the sensor and sends it through a simulation of real film: exposure, development, printing and scanning, modeled from measured spectral data. You see the result while you frame the shot, not after.

## A few words from me

Unspektrawesome is a hobby. I build it in my free time, out of love for film photography and for that particular way film turns light into color.

It's a one-person project, and most of the code is written with the help of large language models. I decide what the app should do, test every change on real phones and keep what works. Regardless of the use of LLMs, a lot of time, money (including devices bought to test different SoCs) and passion has gone into this project.

**This is a work in progress.** Things change often, some corners are rough, and some ideas are half finished. If something breaks, that's part of the journey, not the end of it.

## Where the look comes from

The film simulation at the heart of this app is not mine. It stands on the work of two people:

- **[Andrea Volpato](https://github.com/andreavolpato/spektrafilm)** created **spektrafilm**, a film emulation project that models how light passes through film and paper: how each emulsion layer responds to light, how the negative develops, how it's printed and how the print is scanned. Its film and paper profiles are built from measured spectral data.
- **[chaert-s](https://github.com/chaert-s/spektrafilm-ofx)** turned spektrafilm into **[spektrafilm OFX](https://spektrafilm.114c.de/)**, a plugin for video and photo editing software, which is the version this app was ported from.

Unspektrawesome is an unofficial, partial Android port based on that work. It is **not affiliated** with the original spektrafilm project. The spektrafilm name and the original work belong to their authors. All credit for the science and the profiles goes to them. The mistakes in this port are my own.

## What it does

- **A live RAW viewfinder** that runs the film simulation on your phone's graphics chip as you frame. It goes through the same pipeline as your saved photos, just at a lower resolution. There's also an ISP viewfinder, which starts from the phone's own processed camera feed instead, so it's an approximation rather than an exact preview.
- **More than twenty film stocks and several print papers,** from Portra and Ektar to Velvia and Tri-X, with controls for grain, diffusion and the look of the print.
- **Photos saved as JPEG,** with the original DNG (the raw file) kept alongside if you want it.
- **Shoot now, develop later:** save the raw shots and let the Gallery develop them afterward, entirely offline on your phone.
- **Video** with the film look applied, gyro-based stabilization and a choice of compressed or uncompressed audio.
- **Panorama** and **multi-frame merge** for cleaner low-light shots.
- **Focus tools** such as focus peaking and a focus loupe, plus lens profiles, color calibration files and hot pixel removal.
- **Custom film profiles** you can save, share and import.
- **13 languages,** plus your own translation, which you can import as a custom language.

## Differences from spektrafilm

This port does not match the original pipeline one to one, and results can differ from the desktop version.

To run in real time on a wide range of Android phones, from older models to current flagships, some parts were simplified or changed:

- **Precision traded for speed** in places, for example using lighter number formats on the graphics chip.
- **A lighter viewfinder on older phones.** On older phones, the viewfinder takes a few shortcuts in its processing to stay responsive.
- **Parts of the pipeline were reshaped** to fit how Android cameras deliver their data, and to work around graphics drivers that misbehave on specific chips.

Some of these changes reduce precision and accuracy compared with spektrafilm.

## Will it run on my phone?

Unspektrawesome is demanding. It needs:

- Android 11 or newer
- A 64-bit ARM phone (practically every modern Android phone)
- A graphics chip with Vulkan 1.1 support (Vulkan is the modern graphics system the app runs on)
- A camera that gives apps access to its raw sensor data

It has been tested on a range of phones, from current flagships to older ones such as those with a Snapdragon 865. It runs on older phones too, but the viewfinder is slower and photos take much longer to process than on flagships. Phones it hasn't been tested on may behave differently.

Whatever the phone, expect higher power use, more heat and faster battery drain than with your stock camera, especially during long sessions.

## Building it yourself

You'll need:

- A Java Development Kit (any recent version starts the build, and the build downloads Java 21 for itself)
- The Android SDK, most easily installed with [Android Studio](https://developer.android.com/studio)
- Python 3, with the `numpy`, `scipy` and `colour-science` packages (the build uses them to generate the film data):

  ```
  pip install numpy scipy colour-science
  ```

Then build a debug version:

```
gradlew.bat assembleDebug      # Windows
./gradlew assembleDebug        # Linux and macOS
```

The app ends up in `app/build/outputs/apk/debug/`. On Windows, keep the project in a short folder path; very deep folders can run into Windows' path length limit. So far the build has been tested on Windows only.

## Standing on the shoulders of others

Beyond spektrafilm, this project uses or adapts work from:

- **[RawTherapee](https://github.com/Beep6581/RawTherapee):** the AMaZE color reconstruction for saved photos, by Emil Martinec
- **[darktable](https://github.com/darktable-org/darktable):** the highlight recovery ("inpaint opposed") and the Shadows control
- **[Gyroflow / telemetry-parser](https://github.com/AdrianEddy/telemetry-parser)** by AdrianEddy: the motion data format written into videos
- **[c-blosc](https://github.com/Blosc/c-blosc)** with zstd, zlib-ng, FastLZ and bitshuffle for compression
- **[JetBrains Mono](https://github.com/JetBrains/JetBrainsMono)** as the app's font, and **[Lucide](https://lucide.dev)** icons

Credits and license notices are kept next to the code that uses them.

## Reusing this work

You're welcome to learn from this project, fork it and reuse its code under the license below. If you do, please give credit where it belongs:

- **Any part of Unspektrawesome:** credit **JohnTheFarm3r** and link to [github.com/JohnTheFarm3r](https://github.com/JohnTheFarm3r).
- **The spektrafilm parts,** meaning the film simulation, its profiles and anything derived from them: credit the original developers, **Andrea Volpato** ([github.com/andreavolpato/spektrafilm](https://github.com/andreavolpato/spektrafilm)) and **chaert-s**, the developer of spektrafilm OFX ([github.com/chaert-s/spektrafilm-ofx](https://github.com/chaert-s/spektrafilm-ofx)).

Please keep the credits and license notices in the code intact. The license requires it, and so does the spektrafilm profile license.

## License

Copyright (C) 2026 JohnTheFarm3r

Unspektrawesome is free software under the [GNU General Public License version 3](LICENSE).

<div align="center">

*Made with patience, curiosity and a lot of test shots.*

</div>
