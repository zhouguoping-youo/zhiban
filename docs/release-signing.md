# Release signing

Release packaging fails closed unless all four signing values are provided. Keep the keystore and passwords outside this repository and inject them from the local Gradle user home or the CI secret store.

Supported Gradle properties:

- `zhiban.release.storeFile`
- `zhiban.release.storePassword`
- `zhiban.release.keyAlias`
- `zhiban.release.keyPassword`

Equivalent environment variables:

- `ZHIBAN_RELEASE_STORE_FILE`
- `ZHIBAN_RELEASE_STORE_PASSWORD`
- `ZHIBAN_RELEASE_KEY_ALIAS`
- `ZHIBAN_RELEASE_KEY_PASSWORD`

Run `./gradlew :app:assembleRelease` only after the production keystore has been backed up and its recovery owner is recorded. The build enables APK Signature Scheme v1 through v4 where supported by the Android build tools.

Never commit a keystore, `signing.properties`, passwords, or generated signing reports. The repository `check` task also scans tracked text sources for credential-shaped values.
