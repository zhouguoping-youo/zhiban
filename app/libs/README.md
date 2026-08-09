# SQLCipher dependency provenance

The app uses the official SQLCipher for Android Community artifact from Maven Central:

- Coordinate: `net.zetetic:sqlcipher-android:4.15.0@aar`
- Upstream: <https://github.com/sqlcipher/sqlcipher-android>
- Repository: Maven Central only, as enforced by `settings.gradle.kts`
- Expected native ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`

Do not reintroduce an unverified local AAR. Version upgrades require a reviewed dependency change and full encrypted-database device migration tests.
