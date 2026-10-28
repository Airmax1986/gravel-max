# Changelog

## 1.0.1

Documentation only; routing behaviour is unchanged from 1.0.0 (the profile
differs only in its version comment).

- Add bikerouter.de usage: upload the profile through the BRouter-Web profile
  editor. Checked on 2026-10-28 against bikerouter.de's engine (BRouter 1.7.9):
  upload accepted and a test route computed.
- Separate bikerouter.de from the Bikerouter / Wegwiesel app, which the
  integration guide previously covered alone.

## 1.0.0 — first release

Original gravel-max BRouter routing profile, released under MIT.

- Surfaces: mapped natural surfaces and gravel are preferred; generic
  `unpaved` sits just above gravel. A missing `surface` stays unknown on
  tracks and paths and is never read as gravel; on roads it is costed as
  asphalt. Cobblestone, mud, clay and loose sand cost more than other paved
  surfaces; `smoothness=bad` is a moderate penalty on natural/gravel ways and a
  larger one elsewhere. `mtb:scale` applies to paths, bridleways, tracks,
  footways and cycleways.
- Roads: quiet local roads remain connectors; tertiary, secondary and primary
  roads carry rising costs, raised further by a posted rural speed limit
  (70 / 80 / ≥ 90 or `rural`). Trunk roads and motorways are excluded.
  Estimated traffic adds a bounded cost; reversible one-ways stay routable at
  a higher cost.
- Elevation: climb and descent costs are set per way — 20 / 8 on mapped
  natural, gravel and generic `unpaved` surfaces, 55 / 18 on paved ways, ways
  without a surface tag and loose or muddy surfaces (cutoffs 2 %). Ridge
  tracks are no longer traded for valley asphalt on climb cost alone; steep
  paved shortcuts stay penalised. The reduced climb cost follows the natural
  and gravel surface preferences (`unpaved` needs both).
- Options (`%param%`-annotated): natural and gravel preference, singletrails,
  traffic estimate, steps, ferries, and opt-in noise and open-land (forest)
  terms that are off by default; the forest term treats a way without a forest
  class as open land, and neither term goes below the plain cost.
- Access and barriers: explicit bicycle permissions, one-way and contraflow
  rules, blocked walls and fences; gates, bollards and kerbs cost by
  difficulty; logs and similar obstacles are costly but passable.

Validation scope: 193 contract assertions on each of the 1.7.8 and 1.7.10
lookup tables (including per-way climb costs and the surface switches), 13
synthetic real-engine routes with 130 assertions on 1.7.8, a 115-archetype cost
table under the default options and each option flipped (1035 rows), identical
on both tables and pinned to `tests/brouter/expected-costs.tsv`, and a
lookup-value check (`scripts/check_lookups.py`). Lookup compatibility: BRouter
1.7.8 through 1.7.10 lookups (lookupversion 10 and 11); brouter.de results are
directional only.

A brouter.de comparison on 11 routes around Bad Iburg and Tecklenburg against
the pre-tuning profile met every gate: natural/gravel/unpaved share 27.2 %
vs 20.7 % (+6.5 points, target +3), tagged-paved share 51.8 % vs 52.6 %, less
untagged-road, main-road and fast-road distance, total length +0.8 %, less
repeated distance (14.7 vs 16.1 km), ascent +6 %. These are the same 11 routes
that were used to choose the climb costs, so the result is in-sample. The
paved share fell only on the round trips (45.4 → 43.4 %); on the
point-to-point routes it rose (64.2 → 65.3 %). This is directional evidence
from one region and one map date; synthetic tests and brouter.de probes are not
field validation.

On a stock BRouter 1.7.8 server, out-and-back spurs at via and round-trip
points are cut by default, so round trips come out shorter than on brouter.de;
`profile:correctMisplacedViaPoints=0` keeps them.
