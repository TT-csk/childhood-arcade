# Third-party notices

- JSNES 2.1.0, Ben Firshman and contributors, Apache-2.0. The vendored browser build is transpiled to ES5 without changing emulator logic. Full license: app/src/main/assets/vendor/JSNES-LICENSE.txt. Upstream: https://github.com/bfirsh/jsnes
- core-js-bundle 3.x, Denis Pushkarev and contributors, MIT. Full license: app/src/main/assets/vendor/CORE-JS-LICENSE.txt. Exact version in package-lock.json. Upstream: https://github.com/zloirock/core-js
- Noto Sans SC, Google and contributors, SIL Open Font License. The UI font is a text subset retrieved through Google Fonts; license: app/src/main/assets/fonts/OFL.txt. Upstream: https://github.com/google/fonts/tree/main/ofl/notosanssc
- Classic NES game data and resulting frame captures are local test inputs. Their public redistribution rights have not been confirmed. They are excluded from the separately exported open-source ZIP and are not covered by the application's MIT license.

## Optional arcade runtime (0.3.0)

- FBNeo core, built for EmulatorJS 4.0.11 and archived under CDN 4.0.12. Core archive URL and SHA-256 are pinned in `scripts/prepare-arcade-runtime.py`. The existing JavaScript glue is wrapped as an ES5 factory with its OpenAL context exposed; Binaryen 132 lowers WASM sign-extension operations to WASM MVP. No game data or emulator behavior is patched. Runtime preparation is reproducible using the supplied scripts and lockfile.
- FBNeo is subject to **non-commercial terms**, including restrictions on commercial use and donations; it is not MIT-licensed. See `licenses/FBNEO.txt` and https://github.com/finalburnneo/FBNeo . The RetroArch frontend retains GPL obligations: `licenses/RETROARCH-FRONTEND.txt`, source https://github.com/EmulatorJS/RetroArch (legacy branch master). EmulatorJS upstream: https://github.com/EmulatorJS/EmulatorJS . This project does not assert that the downloaded historical binary has independently verified, exact corresponding build sources. Resolve that before publicly redistributing the optional binary.
- The app's own UI and adapter source are MIT. The public source ZIP omits the optional compiled FBNeo runtime, all classic game ROMs, NeoGeo BIOS, and game frame captures. The optional runtime can be prepared separately for local evaluation. Public publication of an arcade APK requires reviewing upstream binary/source obligations and obtaining permission for any bundled game/BIOS data.
- KOF97/KOF98 and NeoGeo BIOS are local test inputs, with exact file hashes and acquisition provenance in the local `ROM_SOURCES.json`. Public redistribution authorization is unconfirmed; availability online does not grant redistribution permission. These assets are not covered by this project's MIT license.

## Native NES television host (0.5.3)

- FCEUmm, GPL-2.0. The core reports `(SVN) e0f3086`, matching full commit e0f3086a8104f8fbbe49a2615b10401504a9533f. Full source archive is third_party/fceumm-source-e0f3086.tar.gz; license is licenses/FCEUMM.txt. Its binary and source archive checksums are recorded in NATIVE_CORE_ORIGIN.json. The public source ZIP omits compiled libraries and classic games. Source may be built using the included Android.mk and an Android NDK; prepare-native-nes.py provides this route and changes the save identity for separately built cores. Matching source revision does not assert bit-identical compilation.
- JNA 5.13.0, Apache-2.0 OR LGPL-2.1. License: licenses/JNA.txt. Prepared from the hash-pinned official Maven AAR; compiled JAR/SO files are omitted from the source ZIP.
- The combined Android build uses the GPL-2.0 FCEUmm component. Distribution of that combined build must include the applicable license and corresponding-source arrangements. The independent frontend and host code remain available under their MIT license.
