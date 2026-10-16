# Four independent Moviebox H5 playback ports

Reference: Moviebox at `4a4d272f96424a8ab340b8a63a3a44d63d69d0b8`.

Each target module owns `*MovieboxEngine`, `*MovieboxPlayback`, and
`*MovieboxMapping` in its own package. Plugin registration attaches only that
module's context and registers the original TMDB MainAPI. Context/identity
preferences and in-memory sessions are separate. No production source is
included from another provider.

The H5 flow, scoped media interceptor, stream schemas/validation, audio filtering,
captions, signed-policy handling and verified Reacher/House of the Dragon paths
are ported without behavior changes. Unreachable old mobile playback profiles,
Moviebox catalog UI, plugin registration and episode display enrichment are not
copied. Existing TMDB catalogs, metadata, episode stills and indices remain in
the original providers. The optional alternative-title payload is additive;
legacy LinkData and XtreamLinkData remain readable.

Mapping uses exact normalized title aliases, release year and explicit movie/TV
kind from authoritative same-ID detail, rather than rejecting sparse search rows.
It gathers up to eight staged queries (twenty native results per query) and merges
Moviebox's public server-rendered catalog discovery. A failed optional alias does
not discard validated candidates from another query. The public catalog fallback
also works when the mobile session is unavailable; it is Moviebox metadata, never
an alternate media engine. Only inert `__NUXT_DATA__` JSON references are decoded;
no scripts are executed, no browser cookies or credentials are copied.

Ambiguous subject IDs collapse only when the server explicitly declares their
membership in the same `dubs` family, marks exactly one original, and that original
passes independent title/year/type verification. Unrelated same-title identities
remain ambiguous. Native metadata is enriched with a same-ID public detailPath
and declared dubs, then passed as request-local H5 metadata. The original=true dub entry's declared ISO language is preserved in that
context so Korean/Japanese original audio is not mistaken for an unsupported
dub. No new title-specific subject IDs or slugs are hardcoded in the adapter. The playback flow differs from the reference
only by this verified metadata context bridge; its transport/auth/audio/validation
behavior and standalone Moviebox production source stay unchanged.

TV display suffixes such as S1-S3 may identify the series only when the requested
season is inside that range; exact season/maxEp server checks still apply. Movies
use 0/0. Seasons and episodes are never renumbered. Unknown detail years/types,
unrelated editions and unsupported season numbering still fail closed.

Observed Insidious evidence: TMDB 1291595 has release date 2026-08-19; the public
Moviebox page declares subject 8252531803681080504, release date 2026-08-21,
path `insidious-out-of-the-further-06560hmGCP9`, and seven dub IDs plus the marked
original. Sanitized metadata and a minimal equivalent Nuxt reference table are in
`fixtures/insidious-public-*`. These are catalog observations, not live media proof.

Every API request is bounded; cancellation propagates. Mapping plus playback
has a 150-second overall budget. Media transport keeps the reference connect,
read and call limits. No credentials, response bodies or complete signed media
URLs are written to diagnostics.

## Verification

```sh
python3 AdiDrakor/tests/run_migration.py
# Optional live probe, separate from fixture/device proof:
python3 AdiDrakor/tests/probe_insidious_catalog.py
python3 -m unittest discover -s Moviebox/tests/diagnostics -p 'test_*.py'
./gradlew :AdiDrakor:assembleRelease :AdiFilmSemi:assembleRelease :AdiXtream:assembleRelease :Adicinemax21:assembleRelease
./gradlew makePluginsJson
python3 AdiDrakor/tests/verify_artifacts.py /path/to/distribution /path/to/previous/plugins.json
```

The runner first executes the unchanged Moviebox regression suite. It then
compiles each production module separately against the actual CloudStream SDK,
checks byte parity of the H5 flow/interceptor and playback helper, and replays
the original playback regression/integration fixtures against every local port.
Additional mapping tests cover identity, ambiguity, missing seasons, cancellation,
timeout and the actual target MainAPI-to-H5 path using a local HTTP media server.
Test sources/dependencies are never packaged into the plugins.

Fixture success does not prove live service or Android playback. In this
execution environment the real Moviebox authentication request returned HTTP 403
with `Service not available in current region`; no geographic workaround was
used. Device playback, Android UI and buffering require device verification.

The distribution workflow verifies all previously listed plugin identities are
retained and checks the four target manifests plus standalone Moviebox, DEX,
versions, byte sizes and SHA-256 hashes before publication. It requires the
existing builds branch and performs a normal append-only push, never an orphan
branch recreation or force push. The root Gradle configuration is unchanged.

## Identity-aware staged discovery (recording 585560.mp4)

The recording shows Spider-Man: Brand New Day returning no links while other
selected titles play. Its public catalog declares subject 6026412232966389904,
path `spider-man-brand-new-day-Aea8ARY1bb7`, movie type and year 2026. Public H5
returned one DASH and three direct candidates in this environment. This does
not establish that every CDN resource validates or that Android playback passes.

The MainAPI now carries the selected TMDB/IMDb identity, synopsis, runtime and
principal cast in optional playback payload fields. Old payloads still retain
their selected TMDB ID. Matching checks the authoritative TMDB search for
same-title/same-year works: one verified work can proceed; ambiguous works need
an exact external-ID anchor, two matching cast names, or a distinguishing plot
fingerprint (with runtime support for shorter summaries). Conflicting IDs are
always rejected. Truncated TMDB result sets never establish uniqueness.
Release-year differences may be accepted only with an exact external-ID match.

Discovery uses staged exact/original/alternative titles, punctuation variants
and an optional franchise prefix, up to eight queries. Broader queries never
relax the final selected-work checks. Every result in the current query is
validated; a trusted primary match can return without waiting for slow unused
aliases. Sparse native/public metadata is merged without losing usable cast or
synopsis fields. Temporary detail failures may retry once; contradictions stay
rejected. Movie/TV kind and exact server season/episode checks remain in force.

The added fixtures retain only public catalog metadata for the recorded title.
Tests include the actual MainAPI-to-H5 subject/path contract, same-title/year
work ambiguity, external-ID conflicts, wrong stories, typed work IDs, search
truncation, sparse metadata, bounded retry and skipped unnecessary aliases.

Alternative-title-only matches require corroboration even when the primary-title
TMDB search has one result, preventing cross-film alias collisions.
