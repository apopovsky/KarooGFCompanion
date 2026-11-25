# Repository Guidelines

## Project Structure

- `app/` contains the Android extension, Karoo integration, UI, resources, and release manifest.
- `shared/` contains route parsing, power target parsing, pacing calculations, Canvas rendering, and JVM unit tests under `src/test/`.
- `simulator/` contains the local simulator app and bundled 7 Lagos GPX; it is included only when `KarooExtensionSimulator` and `simulator/` exist.
- `assets/` holds release assets; `.github/workflows/` defines CI and release automation.

## Build and Development

Use JDK 17 and an Android SDK. Configure `sdk.dir` in `local.properties` and GitHub Packages credentials as Gradle properties `gpr.user` and `gpr.key`, or environment variables `USERNAME` and `TOKEN`.

- `./gradlew :shared:testDebugUnitTest :app:assembleDebug` runs the CI unit tests and builds the debug APK.
- `./gradlew :simulator:assembleDebug` builds the simulator when its sibling dependency is available.
- `run_simulator.cmd` launches the local simulator on Windows.

On Windows, `gradlew.bat` can replace `./gradlew`.

## Style and Testing

Follow existing Kotlin and Android conventions: four-space indentation, `UpperCamelCase` for types, `lowerCamelCase` for functions and properties, and descriptive resource names such as `remote_gf_pacing.xml`. Keep shared route and rendering logic in `shared/`; put device-specific integration in `app/`.

Use JUnit for unit tests, with coroutine-test where needed. Name test classes after the subject (for example `PowerTargetParserTest`, `RoutePacingEngineTest`), and add focused cases for changed behavior and edge conditions.

## Security

Keep credentials, signing keys, and local configuration out of commits.
