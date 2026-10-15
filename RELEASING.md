# Releasing

Package identity, version and supported integrations are maintained in [plugin.json](plugin.json). Release requirements, artifact roles and acceptance targets are maintained in [release-policy.json](release-policy.json). The annotated Git tag is the exact stable SemVer, without `v`.

## Prepare locally

Use the toolchains and checks in [DEVELOPMENT.md](DEVELOPMENT.md), plus Ruby and GnuPG. DCloud SDKs are compile inputs and are not redistributed. Configure the signing identity in GPG; keep private keys and passwords outside the repository. The manual Central Portal upload needs no API token.

Start from committed, clean product source. Keep `plugin.json`, RN/UNI package metadata and [CHANGELOG.md](CHANGELOG.md) consistent. Resolve the root Swift package's official dependencies into an ignored Xcode `SourcePackages` directory, then run:

```sh
python3 scripts/prepare-release.py \
  --source-packages /absolute/path/to/SourcePackages \
  --dcloud-android-aar /absolute/path/to/uniapp-v8-release.aar \
  --dcloud-ios-sdk /absolute/path/to/classic-ios-sdk \
  --signing-fingerprint '<full public GPG fingerprint>'
```

The command materializes and packs the complete RN npm package, then builds and signs the Maven modules with POM, Gradle metadata, source and documentation jars. It builds the iOS device frameworks for both UNI packages and the native XCFramework, plus separate simulator slices. It verifies signatures and consistency of the native binaries embedded in each package.

Both UNI archives include the same inactive optional Android vendor kit. Consumer AppKeys, service JSON, signing and DCloud authorization stay outside distribution archives. Consumer installation is described in the [UNI guide](adapters/uniapp/README.md#安装插件).

The output is `dist/release/<version>/candidate.json` and its referenced files. `--rehearsal` permits uncommitted source and omitted signing for local checks, writes under `dist/rehearsal/<version>`, and cannot be used as a formal candidate. Preparation refuses to overwrite an existing frozen candidate or rehearsal; preserve it separately before preparing a replacement. Once accepted, publish those exact files without rebuilding, re-signing or repacking.

The public RN archive is named `avvessa-react-native-<version>.tgz`; its npm package name remains `@sandrox/avvessa`. If an accepted candidate uses a different archive filename, preserve the candidate and receipt, and place a byte-identical copy under the public name beside the candidate. Write its `.sha256` using the accepted archive hash and the public filename. The GitHub and npm publication commands verify both before printing their paths. This filename correction does not rebuild or repack the archive.

## Verify the frozen candidate

Use the separate verifier repository named in the release policy. Its `verification/avvessa/README.md` owns consumer preparation, target commands, device procedures and acceptance receipt generation. Its independent registry must match the product release policy.

Acceptance binds the source commit, artifact bytes and required checks. Build results do not establish push delivery; the required device observations must cover the relevant configuration and scenarios. Earlier results may be reused only after reviewing implementation equivalence and applicability, with their original provenance retained.

After acceptance, create the annotated tag on exactly the candidate's source commit. Publication verification requires that tag, clean source, a matching receipt and valid signatures:

```sh
ruby scripts/verify-publish-candidate.rb \
  --candidate /absolute/path/to/candidate.json \
  --acceptance /absolute/path/to/accepted.json
```

If the working branch has moved beyond the release commit, pass `--source /absolute/path/to/clean-tag-checkout`. `scripts/publish-accepted.rb` accepts the same options plus `--channel github|maven|npm|uniapp` and prints the files and manual steps for that destination. These commands verify local inputs; they do not upload or publish.

## Publish accepted files

Before the first publication, inspect existing GitHub tags/assets, Central coordinates, npm package/version and the marketplace version. Do not overwrite a public version or move a published tag. If a prior action was interrupted, inspect the destination before retrying.

1. **GitHub Release:** use the existing tag, title `Avvessa <version>`, and a short user-facing summary linked to the maintained integration guides on the default branch and the tagged changelog. Upload only the files printed for the `github` channel: XCFramework ZIP, native manifest, RN tarball and checksum, and two UNI ZIPs with checksums. SPM and configuration tools use the Git repository at that tag; GitHub also provides its automatic source archives. The candidate source ZIP and its checksum remain local verification inputs. Do not upload the whole `dist` directory or the Central bundle.
2. **Maven Central:** in [Central Portal](https://central.sonatype.com/publishing), upload the accepted `avvessa-<version>-maven.zip` with deployment name `avvessa-<version>`, user-managed publication. Wait for validation and let the operator publish. Confirm all eight coordinates are publicly downloadable.
3. **Android mirrors:** manually run **Mirror Android AAR** from the default branch with `version`. It checks the existing tag/manifest, downloads all eight AARs from Central, verifies their accepted hashes and adds mirrors plus checksums to the existing Release. It refuses existing filenames rather than replacing them.
4. **npm:** after GitHub Release and Maven Central are available, publish the accepted `avvessa-react-native-<version>.tgz` with `npm publish <tarball> --access public --ignore-scripts --registry https://registry.npmjs.org/`. For the first publication, complete interactive login/2FA yourself. Once the package exists and the next automated release is ready, configure [Trusted Publishing](https://docs.npmjs.com/trusted-publishers/) for owner `sandroxy`, repository `avvessa`, workflow `publish-npm.yml`, allowing `npm publish` and leaving the environment name empty. A new configuration must complete its first successful publication within two days or it expires. Later releases can use the manually dispatched **Publish npm** workflow. It verifies the annotated source tag, accepted tarball and public Maven bytes before publishing the same tarball through OIDC; it never rebuilds. Verify the downloaded npm tarball matches the accepted SHA-256.
5. **UNI marketplace:** use the verifier's shared UNI upload preparation, linked from its `verification/avvessa/README.md`, to restore the accepted package in the fixed UTS or x consumer. Open that project in HBuilderX, then right-click `uni_modules/Sandrox-Avvessa` → publish. Use the packaged `package.json`, `readme.md` and `changelog.md`. The upload module must not contain the application's consumer AAR, credentials or selected vendor configuration; do not upload the whole test project. Classic UTS and uni-app x share this package; legacy remains the compatibility/offline ZIP. Review marketplace metadata normalization separately from changes to executable files or dependency configuration.
6. **Public verification:** manually run **Verify UniApp Release Assets**, supplying `version` and the two accepted ZIP hashes. It checks GitHub assets and embedded native lineage; this is not a marketplace import check. Prepare an empty module location in the same fixed consumer, import the published market package and compare its executable/native/configuration content against the accepted package before restoring local test configuration.

The public workflows use manual dispatch only; creating a tag or Release does not automatically publish other channels. They never build a replacement artifact and require no private-repository workflow.

Documentation corrections belong in the maintained guides and the release page, with links to the affected installation steps. Do not replace a published archive to change its bundled README. For marketplace description corrections, use DCloud's [Modify plugin basic information](https://doc.dcloud.net.cn/uni-app-x/plugin/uni_modules#修改插件基本信息) after publication; it supports updating `readme.md` separately from publishing a new plugin version. Use the maintained UNI guide with repository-relative links converted to public URLs, and verify the resulting marketplace page. Do not rebuild or repack accepted archives for a description update; review description changes separately from executable files, resources and dependency configuration during the marketplace import check.

Confirm GitHub downloads, Central coordinates, SPM tag resolution, npm tarball identity and marketplace import before promoting the verifier's stable lock. Ordinary documentation corrections go to the default branch. Published tags and files stay unchanged; changes to distributed package contents require a new version.

After promotion, retire completed consumers with the verifier's documented workspace command. Keep the current published inputs and, when needed, one version in development. UNI selects one input set per mode in a fixed project; candidate hashes identify immutable inputs and evidence, not additional full application checkouts. Retain frozen candidates, their referenced artifacts, receipts and original logs when retiring generated projects. The product build workspace remains `.build/release`; publication does not need a new build directory or another copy of the toolchains.
