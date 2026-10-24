# Third-party software

Qihuang Schedule's original code and artwork are licensed under Apache-2.0. Third-party
components retain their own copyright and license terms.

The complete dependency notices bundled with the application are in
[`androidApp/src/main/assets/third-party-notices.txt`](androidApp/src/main/assets/third-party-notices.txt).
The Windows distribution also retains the bundled OpenJDK runtime's `legal` directory.

Main components include miuix, Kotlin, Compose Multiplatform, AndroidX, kotlinx
libraries, Apache POI, jsoup, JNA, Log4j, SLF4J, Gradle Wrapper, and desugar_jdk_libs.
The libxposed API is a compile-only dependency, supplied by the device framework;
its implementation is not bundled in the APK.

- miuix: <https://github.com/compose-miuix-ui/miuix> (Apache-2.0)
- libxposed API: <https://github.com/libxposed/api> (Apache-2.0)
- libxposed Service 102.0.0: <https://github.com/libxposed/service> (Apache-2.0), bundled in Android for framework activation callbacks; the hook API remains compile-only.
- SheetJS CE 0.20.3: <https://docs.sheetjs.com/> (Apache-2.0), vendored only in the HarmonyOS prototype; see `harmonyApp/entry/src/main/resources/rawfile/vendor/SHEETJS-LICENSE`.
- Gradle: <https://github.com/gradle/gradle> (Apache-2.0)

The XiaoAi integration is an independent adapter to the assistant's existing
official timetable page and session. The xiaoai-import project was consulted as
an interoperability reference; its source is not included in this repository.
School and Xiaomi services, names, and marks belong to their respective owners.
This project is not an official university or Xiaomi application.
