# Moviebox playback regression

Run `python Moviebox/tests/run_regression.py` from the repository root with Java 17 and Python 3.
The runner downloads test tools into a temporary cache, compiles the production module against the
real CloudStream API **without shims**, and separately runs the JVM fixtures. `CLOUDSTREAM_JAR`
can select the same SDK jar used by the Gradle build. Test Android/app/link-builder shims exist
only in the fixture compilation; none are packaged in the plugin. The real ExtractorLink,
NiceHttp, OkHttp, JSON and coroutine classes are used by the integration harness.

The fixtures are synthetic and sanitized. Passing them does not prove MovieBox live service
availability or Android playback. There are no customer tokens, cookies or account credentials.

## Reference analysis

- Repository baseline: `e28f2caa3bbf73b54a7ec8906dce5ed1b0449f5f`.
- Attached `MovieBoxProvider.cs3`: manifest **36**, plugin `com.MovieBox.MovieBoxProviderPlugin`.
- Attachment SHA-256: `742a29f499669c954b8993f283ce3b45b562f29bf9156486e2bb6e36e871ac9b`.
- Inspected `classes.dex` with JADX 1.5.3 and Androguard instruction disassembly.
  JADX reported two failed/partial methods; original DEX instructions were checked for
  `loadLinks`, `loadNativeMobileStreams`, `loadCaptions`, `extractPolicyResource`,
  `buildCanonicalString`, `generateXTrSignature`, and `loadLinks$7.invokeSuspend`.
  The native callback and web `emitWebStream` continuation/callback were also inspected.
- Existing comments referring to v47 were not evidence that the attachment was v47.

| Route | Request and response evidence in v36 |
| --- | --- |
| Existing v2 | Kept from repository: `play-info/v2`, exact subject/se/ep, original blank-header GET canonical and basic/codec request variants. |
| Native | `subject-api/play-info?subjectId=…&se=…&ep=…`; `data.streams[]`, id, url, format, resolutions, codecName, signCookie. Native default JSON canonical uses the **default** protocol HMAC key, not the alternate key used by the old dormant helper. |
| Detail resources | `subject-api/get?subjectId=…` → `data.resourceDetectors[].resolutionList[]` → resourceLink/resolution/se/ep. Serial detail resources must identify the exact episode. |
| H5 | `subject/play` with subjectId/se/ep/detailPath/streamSignType/supportCodecs; `data.dash[]`, `data.hls[]`, `data.streams[]`. detailUrl/detailPath determine web path/referer. WEB_DOMAINS are routing aliases. Server-declared dubs supply subjectId/lanName/detailUrl. |
| Signed media | Policy resource from Edge-Cache urlprefix or CloudFront Statement Resource. Native passes Cookie; H5 passes Cookie and signHeaderKey (default X-MB-Token), web Origin/Referer/UA. Restriction override headers present in the reference are deliberately omitted. Bearers are not sent to web aliases or media. |
| Captions | get-stream-captions(subjectId, streamId), independently get-ext-captions(subjectId, resourceId, episode=0), both return `data.extCaptions[]`. The latter's episode=0 is the v36 resource-scoped API parameter, not an instruction to change the video episode. Existing v2 caption authentication is retained. |

`playPath`, `playSource` and sports domains belong to the sports branch. They are not movie
fallbacks. No sports module, catalog, episode listing, posters, search, trailer, registration
or unrelated provider was ported or modified.

## Changes and limits

Removed premature failure on normal bearer bootstrap, wrong-episode retries, callback success
based only on a URL string, and DASH-first preference. Native and H5 use separate request
profiles and add validated choices. Queries on signed media remain intact. Policy-derived
resources and original media are checked independently; policy cookies do not cross to an
unrelated original host. Captions are requested for validated stream/resource IDs and deduplicated.

Probes use GET/Range, cap reads at 4 KiB for media and 256 KiB for manifests, and follow at most
three redirects. Candidate validation runs with concurrency three. HLS probes a variant,
initialization/key where relevant, and a media segment; DASH probes a compatible video and
audio representation's initialization/media URLs. Protected DASH and HLS DRM methods are rejected.
Header scope is enforced on probe redirects and actual player segment/redirect requests.
When video already exists, redundant H5 routing probes are capped at two aliases; when
video routes failed, the complete reference alias list remains available.

This is a bounded startup check, not a guarantee that every future segment, rendition, codec,
seek, device decoder or expiring URL will work. Unsupported manifest arrangements are rejected
rather than emitted as false positives. Signed query variants are retained because dropping
unknown parameters can invalidate authorization. Only an HTTP fragment is deduplicated away.

## Validation

- 19 schema/media regressions: PASS.
- 12 provider/crypto/local HTTP integration scenarios: PASS.
- Production Kotlin compilation against real CloudStream SDK: PASS.
- Android Gradle `:Moviebox:assembleDebug :Moviebox:make`: PASS (Java 17, SDK 36); plugin manifest version 13.
- Live HTTP from execution environment: BLOCKED — api3 returned HTTP 403,
  `Service not available in current region`; no region circumvention was used.
- Real movie/series Android playback: NOT_TESTED; requires a device in a supported region.

The integration scenarios cover movie 0/0, serial 1/1 and 2/4, unavailable bearer,
endpoint errors, HTML masquerading as video, independent subtitle failure, H5 routing,
actual signed manifest/segment requests, credential stripping after redirects, bounded
reads when Range is ignored, and false/zero callbacks when all routes fail.
