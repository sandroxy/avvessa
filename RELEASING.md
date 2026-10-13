# Release preparation and publication

Avvessa publishes native Android/iOS and UNI legacy, classic UTS and uni-app x. Versions come from `plugin.json`; the annotated Git tag is the exact stable SemVer, without `v`. Android coordinates use `io.github.sandroxy`; the UNI module is `Sandrox-Avvessa`. React Native, npm and Flutter are not publication targets.

## Prepare locally

Use Xcode, JDK 17 or later, Android SDK 36, Python 3, Ruby, GnuPG and the pinned official DCloud SDKs. The regular build checks are in [BUILDING.md](BUILDING.md). DCloud SDKs are compile inputs only and are never redistributed. Signing is handled by a locally configured GPG identity; do not place private keys or passwords in this repository. Central Portal does not need an API token for the manual upload flow.

Start from committed, clean product source. Keep `plugin.json`, UNI `package.json` and [CHANGELOG.md](CHANGELOG.md) consistent. Resolve the root Swift package's official dependencies into an ignored Xcode `SourcePackages` directory, then run:

```sh
python3 scripts/prepare-release.py \
  --source-packages /absolute/path/to/SourcePackages \
  --dcloud-android-aar /absolute/path/to/uniapp-v8-release.aar \
  --dcloud-ios-sdk /absolute/path/to/classic-ios-sdk \
  --signing-fingerprint '<full public GPG fingerprint>'
```

The command builds all eight Maven modules with POM, Gradle metadata, source and documentation jars, signs each payload and verifies its signature. It builds the iOS device frameworks once for both UNI packages and the native XCFramework. Simulator slices are built separately. It checks that embedded core/vendor AARs match Maven bytes and that the UNI iOS core matches the XCFramework device slice.

The two UNI archives are `avvessa-uniapp-<version>.zip` (UTS) and `avvessa-uniapp-legacy-<version>.zip`. Each has `package.json` at its root; extract into `uni_modules/Sandrox-Avvessa` or `nativeplugins/Sandrox-Avvessa`. Both include the same inactive optional Android vendor kit. Consumer AppKeys, service JSON, signing and DCloud authorization remain outside distribution archives.

The output is `dist/candidate.json` plus the files it names. The independent contract is [release-policy.json](release-policy.json). `--rehearsal` allows dirty source and omitted signing for local build checks, writes under `dist/rehearsal`, and explicitly produces no acceptance-eligible candidate. Never treat that output as a formal release. A formal candidate protects itself from another preparation overwriting its files. Preserve a failed or superseded candidate separately before explicitly preparing a replacement; do not alter accepted bytes.

## Verify the frozen candidate

The separate integration repository registers the same artifact roles and eight automated targets. Its resolver verifies file lengths, SHA-256, candidate identity, source state and the independently registered matrix. Run its `verification/run-acceptance.rb --candidate /absolute/path/to/candidate.json --target <target>` from a clean verifier commit. Targets are `android`, `ios`, and `uniapp-{legacy,uts,x}-{android,ios}`.

These consumers use the frozen Maven repository, source SPM ZIP, XCFramework and UNI archives. They never substitute the current product checkout. Compilation, callback generation and dependency linkage do not prove push delivery. Record actual device evidence for the eight corresponding `*-push-device` targets; reuse earlier observations only with an explicit applicability review of native bytes, wrappers, consumer configuration and scenario coverage. Never describe reused evidence as fresh device testing.

After all required checks have passed, the integration repository's `verification/record-acceptance.rb` produces an accepted receipt. Create the annotated tag on exactly the candidate's source commit only after acceptance. Publication tooling requires that tag, clean source, matching receipt and verified signatures:

```sh
ruby scripts/verify-publish-candidate.rb \
  --candidate /absolute/path/to/candidate.json \
  --acceptance /absolute/path/to/accepted.json
```

`publish-accepted.rb` accepts the same paths plus `--channel github|maven|uniapp`. It only prints the exact files and manual steps. It does not authenticate, upload, publish or rebuild anything. Login, security keys and publication actions remain with the operator.

## Publish accepted files

Before the first publication, inspect existing GitHub tags/assets, Central coordinates and the marketplace version. Do not overwrite a public version or move a published tag. If a prior action was interrupted, inspect the destination before retrying.

1. **GitHub Release:** use the existing tag, title `Avvessa <version>`, and a short user-facing summary linked to the tagged changelog. Upload only the files printed for the `github` channel: XCFramework ZIP, source ZIP and its checksum, native manifest, two UNI ZIPs and their checksums. The source package supports reproducible SPM/offline configuration tooling; SPM's normal installation entry is the Git repository and tag. Do not upload the whole `dist` directory or the Central bundle.
2. **Maven Central:** in [Central Portal](https://central.sonatype.com/publishing), upload the accepted `avvessa-<version>-maven.zip` with deployment name `avvessa-<version>`, user-managed publication. Wait for validation and let the operator publish. Confirm all eight coordinates are publicly downloadable.
3. **Android mirrors:** manually run **Mirror Android AAR** from the default branch with `version`. It checks the existing tag/manifest, downloads all eight AARs from Central, verifies their accepted hashes and adds mirrors plus checksums to the existing Release. It refuses existing filenames rather than replacing them.
4. **UNI marketplace:** in HBuilderX, open the pristine staged UTS module from the accepted artifact directory, then right-click `uni_modules/Sandrox-Avvessa` → publish. Use the packaged `package.json`, `readme.md` and `changelog.md`. Do not upload a consumer copy containing resources, credentials or selected vendor dependencies. Classic UTS and uni-app x share this package; legacy remains the compatibility/offline ZIP. Review marketplace metadata normalization separately from changes to executable files or dependency configuration.
5. **Public verification:** manually run **Verify UniApp Release Assets**, supplying `version` and the two accepted ZIP hashes. It checks GitHub assets and embedded native lineage; this is not a marketplace import check. Separately import the published market package and compare its executable/native/configuration content against the accepted package.

The public workflows use manual dispatch only; creating a tag or Release does not automatically publish other channels. They never build a replacement artifact and require no private-repository workflow.

After the public downloads and marketplace import are confirmed, update only Avvessa in the integration repository's stable lock with the actual URLs, lengths and hashes. Keep it `unpublished` until then. Report GitHub, Central, SPM resolution and market review/import independently. Ordinary documentation changes after release go to the default branch; package content changes require a new version.
