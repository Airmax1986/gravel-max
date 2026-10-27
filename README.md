# gravel-max

A BRouter routing profile for gravel bicycles: rideable natural surfaces and
gravel, quieter roads, and practical paved connections.

The distributable algorithm is **[profiles/gravel-max.brf](profiles/gravel-max.brf)**.
It is an original profile for **BRouter 1.7.8 through 1.7.10 lookups
(lookupversion 10 and 11)**; brouter.de results are directional only. It does
not require the Python discovery engine, personal GPX tracks, a tile server, or
a mobile app.

## Install

1. Install `gravel-max.brf` beside `lookups.dat` in your BRouter server's
   `profiles2` directory, using a compatible segment/lookup dataset.
2. Select `profile=gravel-max` in a BRouter request. For example, with your own
   server:

   ```text
   https://YOUR-SERVER/brouter?lonlats=8.72,50.0025|8.7237,49.9975&profile=gravel-max&format=geojson
   ```

   Coordinates are `longitude,latitude`. This example is a request format,
   not a claim that a public server has gravel-max installed.
   On a stock BRouter 1.7.8 server (including the Bikerouter Docker build),
   out-and-back spurs at via and round-trip points are cut
   (`correctMisplacedViaPoints`), so round trips come out shorter than on
   brouter.de (3–14 % on the evaluation set); add
   `profile:correctMisplacedViaPoints=0` to keep them.
3. Alternatively, load the BRF into a BRouter client that supports custom
   profile text, following that client's profile-import instructions.

See [the profile guide](docs/GRAVEL_MAX_PROFILE.md) for options and behavior,
and [Bikerouter integration](docs/BIKEROUTER_PROFILE_INTEGRATION.md) for the
existing Flutter iPhone application.
Release changes and validation scope are listed in
[PROFILE_CHANGELOG.md](PROFILE_CHANGELOG.md).

## Algorithm and limits

The profile assigns costs to OSM ways, nodes, and elevation changes. Missing
surface tags remain unknown; track grade is a separate suitability clue.
Explicit access and direction constraints are separate from scenic preference.
These are map-data rules, not confirmation of current on-site access or condition.

A BRouter profile does not implement the research engine's regional forest-area
analysis, adaptive corridor search, or whole-loop ranking. It does not guarantee
a surface percentage, exact distance, global loop diversity, or subjective ride
quality. The preference weights are heuristics awaiting field validation.

## Verification

Requirements: Python 3.11+, Git, JDK 17. Clone the pinned BRouter version
(`.tools/` is git-ignored) and run:

```sh
git clone --depth 1 --branch v1.7.8 https://github.com/abrensch/brouter.git .tools/brouter-v1.7.8
python3 scripts/test_brouter_profile.py \
  --source .tools/brouter-v1.7.8 \
  --deps .tools/test-deps --fetch-deps
python3 scripts/check_lookups.py
```

The runner checks the exact BRouter revision, compiles its actual evaluator and
routing engine, verifies dependency hashes, and runs four checks. Tag-level
contracts and the cost table run on both lookup tables (1.7.8 and 1.7.10);
the synthetic OSM → RD5 → route tests run on 1.7.8 only, because RD5 encoding
is bound to the map creator's lookup table. The cost table
(`tests/brouter/CostTable.java`) evaluates every archetype in
`tests/brouter/archetypes.txt` under the default options and with each option
flipped once; both lookup tables must give identical costs, and the result
must match `tests/brouter/expected-costs.tsv` byte for byte, so any cost
change fails until that file is updated deliberately.
Test-only dependencies are downloaded on explicit `--fetch-deps`.
`--java-home` selects a JDK outside `PATH`. Validation reports, logs and the
cost tables go to `output/current/profile-v1-validation/`.
`scripts/check_lookups.py` (offline, no dependencies; defaults to the
`.tools/` paths above, `--lookups` for others) confirms that every `key=value`
the BRF tests is a primary value in the 1.7.8 and 1.7.10 lookup tables
(BRouter's profile parser does not accept lookup aliases). In the development
workspace (not in the release archive), `scripts/cost_table_diff.py OLD.tsv
NEW.tsv` lists the archetypes whose cost changed.

The route fixtures test route choice, start/end geometry, connectivity, explicit
bicycle bans, one-way routing and contraflow. They are synthetic behavioral
evidence, not field trials or proof of quality in all regions.

Development tooling, not part of the release: `scripts/eval_fetch.py` fetches a
fixed set of routes for a profile from brouter.de (rate-limited, logged,
budgeted), `scripts/eval_metrics.py` turns them into surface/road metrics and
the K1–K9 comparison, and `scripts/eval_cases.json` holds the route set.
`scripts/eval_local.py` routes the same cases with the pinned BRouter 1.7.8
under `.tools/` on a locally stored brouter.de map file
(`.tools/segments/E5_N50.rd5`, git-ignored), so candidates can be screened
without live requests; `scripts/eval_breakdown.py` breaks routes down by
highway and surface. Their results under `output/current/profile-eval/` are
development material: brouter.de comparisons, not field validation.

## Publication

The release builder includes only an explicit source/documentation allowlist and
a validation report matching the profile hash. It excludes personal tracks,
geodata, historical research outputs, upstream checkouts, and test dependencies.
The original profile, tooling and documentation are released under the
[MIT license](LICENSE). See [third-party notices](THIRD_PARTY_NOTICES.md) for
separately downloaded test dependencies and data attribution.

After validation, `python3 scripts/build_profile_release.py` creates a
checksummed source archive under `output/current/releases/`.

The development workspace also contains an older Python research prototype.
Its historical v1.0 pilot-audit label is independent of the gravel-max routing
profile's v1.0.0 release.
