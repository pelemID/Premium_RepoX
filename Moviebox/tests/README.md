# Moviebox H5 playback regression

Run `python Moviebox/tests/run_regression.py` from the repository root with Java 17 and Python 3.
The runner compiles production against the real CloudStream SDK **without shims**, then separately
runs the JVM fixtures. Optional `CLOUDSTREAM_JAR` selects the Gradle SDK jar; `MOVIEBOX_TEST_CACHE`
selects a test-only dependency cache. Android/app/link-builder shims are never packaged.
Fixtures are sanitized and do not establish live service or Android playback success.

## Current behavior — plugin version 14

- Media uses **H5 only**: `subject/play` with the exact subjectId, season, episode, detailPath,
  streamSignType and codec flags. No requests to mobile `play-info`, `play-info/v2`, or media
  extraction from native resourceDetectors remain in the resolver.
- Mobile authentication, detail metadata and caption endpoints remain necessary to resolve
  H5 detailPath/server-declared dubbing and preserve subtitles. They are not media sources.
- Metadata tries the modern profile, the established catalog profile if needed, then public
  H5 detail for the **same subject ID**. No title search/fuzzy match or episode substitution.
  An explicit detailPath takes precedence over a stale detailUrl. Flat mobile and nested H5
  subject metadata are checked for the exact identity.
- All reference H5 routing aliases remain eligible if earlier aliases fail or return no usable
  streams, even after another audio subject succeeds. The prior two-alias cap is removed;
  a successful alias is preferred for later audio subjects. The first usable alias supplies
  all its DASH/HLS/direct choices; aliases are not invented as separate video servers.
- Labels use `Asli • Video • H.264`, `Indonesia • DASH • H.265`, etc. Language and codec names
  are normalized, repeated "dub Audio" removed, and quality is left to CloudStream's display.
  Choices are ordered by audio group, descending quality and compatible codec preference.
  Distinct CDN alternatives with otherwise identical labels receive a server suffix.
- Each signed request is validated independently. After validation only, signature rotations
  for the same resource/audio are deduplicated. The actual signed URL is never rewritten;
  content/codec/quality selectors and unknown token parameters remain significant.
- Existing GET/Range bounds, manifest/segment checks, signed Cookie/token scoping, DRM rejection
  and both caption endpoints are preserved. Caption failure never invalidates usable video.

## Reacher investigation — 2026-10-16

Input: subjectId `3620534210064175096`, **season 1, episode 1**, matching the supplied device video.
The `/detail?id=…` URL is a provider identifier, not a browser playback page; the video records
HTTP 404 when it is opened in the browser. The video also shows an App Upgrade Notice from the
old sources, H5 playback of other titles, repeated quality text, and verbose audio labels.

Live H5 requests were made once to each reference alias with the same ID/episode and an empty
path when metadata was unavailable. All seven returned HTTP 200/code 0 with:

```json
{"streams":[],"dash":[],"hls":[],"hasResource":false,"vipLocked":false}
```

Aliases checked: h5-api.aoneroom.com, 123moviesfree.club, movie-box.co, movieboxonline.net,
sflix.film, netnaija.film and movieboxhd.net. Public H5 detail by this exact subject ID returned
HTTP 404 / `TARGET_NOT_FOUND` / `subject not found`. The mobile detail request from the execution
region returned HTTP 403 / `Service not available in current region`.

These observations do **not** prove permanent unavailability or availability in every region,
season, or audio variant. They establish that those exact requests supplied no H5 media to
emit. The provider must return no source rather than an upgrade notice or an unrelated title.
The resolver now checks all eligible aliases and metadata profiles; real device retesting is
still needed to establish whether supported-region metadata yields additional resources.
No restriction override headers, public proxies, paywall or DRM bypass were added.

## Validation

- 22 schema/media regressions: PASS.
- 17 provider/crypto/local HTTP integration scenarios: PASS.
- Production compilation against real CloudStream/NiceHttp/OkHttp APIs: PASS.
- Android plugin packaging: the GitHub Build result is separate from local fixture validation.
- Device playback of this version: NOT_TESTED.

Tests cover absence of v2/native requests, movie 0/0 and serial 1/1 or 2/4, failed bearer,
independent captions, later H5 aliases after an existing success, metadata profile/H5 fallback,
notice rejection, signed manifest/segment headers, cross-host credential stripping, bounded
Range reads, expired signature rotation, clean labels and the observed empty Reacher response.

## Reference

The original H5/auth/policy/caption port was checked against `classes.dex` from the attached
`MovieBoxProvider.cs3`, manifest **36**, using JADX and DEX instruction disassembly.
Attachment SHA-256: `742a29f499669c954b8993f283ce3b45b562f29bf9156486e2bb6e36e871ac9b`.
That reference also contained sports playPath/playSource branches; none were ported.
The current cleanup baseline is `3c2677ac7dfd472a68552606314017758fd67591`.
Catalog, search, posters, details, episode listing, recommendations, trailer and other providers
are unchanged by this cleanup.
