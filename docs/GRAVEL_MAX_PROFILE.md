# gravel-max BRouter profile v1.0.1

`profiles/gravel-max.brf` is an original BRouter 1.7.8-compatible routing
profile for gravel bicycles. It combines per-way riding preference with
separate public-route access and direction checks. Install it on a BRouter
server that serves `profiles2`; clients select it by the profile basename,
`gravel-max`.

## Install and select

Copy `profiles/gravel-max.brf` into the server's `profiles2/` directory and
restart/reload that BRouter instance as its deployment requires. A typical
HTTP routing request sets `profile=gravel-max`. On bikerouter.de and other
BRouter-Web sites, upload the file through the profile editor instead (see
[the integration guide](BIKEROUTER_PROFILE_INTEGRATION.md)). For the
Bikerouter / Wegwiesel app, installation
on a server makes the profile usable by online requests but does not add it to
the app's profile picker or deploy it to the existing public service. The app
registry and server deployment are separate integration steps. The app's
offline routing currently uses another routing model; this file does not add
offline support.

Lookup compatibility: BRouter 1.7.8 through 1.7.10 lookups (lookupversion 10
and 11); brouter.de results are directional only. Every `key=value` the profile
tests is a primary value in both tables (`scripts/check_lookups.py`, included
in the release). Do not substitute a different lookup table without running
that check.

Round trips and via points: stock BRouter 1.7.8 (also the Bikerouter
`Dockerfile.brouter` build) cuts every out-and-back spur at a via or
round-trip point (`correctMisplacedViaPoints`, on by default; brouter.de
1.7.11 keeps them). On the evaluation set, round trips came out 3–14 % shorter
on 1.7.8 than on brouter.de with identical map data, and an out-and-back via
route collapsed from 16.4 to 2.3 km. Send `profile:correctMisplacedViaPoints=0`
with a request to keep the spurs.

## Settings

These profile parameters can be overridden by BRouter callers that support
profile parameters:

| Setting | Default | Effect |
| --- | --- | --- |
| `prefer_natural_surfaces` | `true` | Gives mapped ground, grass, dirt and earth a modest preference and the reduced climb cost (see Elevation). Mud, clay and wood remain roughness penalties. |
| `prefer_gravel_surfaces` | `true` | Gives gravel, fine gravel and compacted surfaces a modest preference and the reduced climb cost. Generic `unpaved` keeps the reduced climb cost only while both surface preferences are on. |
| `prefer_singletrails` | `false` | When enabled, lowers the cost of easy trails: untagged paths and bridleways (×1.12 → ×1.02) and ways tagged `mtb:scale` 0+ or 1 on paths, bridleways, tracks, footways and cycleways (×1.16 → ×1.04, ×1.42 → ×1.08). It does not make difficult MTB trails attractive. |
| `consider_traffic_estimate` | `true` | Applies a moderate multiplier from BRouter's `estimated_traffic_class`; unmapped estimates add no cost. |
| `allow_steps` | `false` | Excludes steps by default. Set `true` to route via them at high cost where the node graph permits it. |
| `allow_ferries` | `true` | Allows ferries as high-cost connectors. |
| `consider_noise` | `false` | When enabled, multiplies ways by BRouter's `estimated_noise_class` (class 2 ×1.03 up to class 6 ×1.4). |
| `consider_forest` | `false` | When enabled, adds a small cost on open land (`estimated_forest_class` 1–2 or no class ×1.08, 3 ×1.04; BRouter assigns no class below a green factor of 0.1). Forest is never rewarded below the plain cost. |

The settings are declared in the BRF as profile parameters; a client must pass
overrides using the parameter mechanism supported by its BRouter integration.
Changing them does not modify the public access policy.
For BRouter HTTP requests, use numeric `1`/`0` overrides such as
`profile:prefer_singletrails=1` or `profile:allow_ferries=0`.

## Cost model

Each way's cost is its length times a product of factors; the numbers below are
the shipped defaults.

**Surfaces.** With the default preferences, mapped natural surfaces (ground,
grass, dirt, earth) cost 1.00, gravel/fine gravel/compacted 1.10 and generic
`unpaved` 1.12. Sealed surfaces (asphalt, concrete, paved, paving stones, sett,
metal) cost 1.32, cobblestone 1.6, wood 1.30, clay 1.48, mud 1.85 and loose
sand/pebblestone 2.25. A missing `surface` tag stays unknown on tracks and
paths (1.20, neutral); `highway=track` never implies gravel. A road without a
`surface` tag (residential through primary, service, cycleway) is costed as
asphalt (1.32), so missing data on roads is not cheaper than tagged pavement.
An untagged grade1 track costs 1.30 × 1.02 = 1.326: not below the bare
asphalt factor, but slightly cheaper than a grade1 track tagged asphalt
(1.32 × 1.02 = 1.346). `tracktype` is a separate rideability factor
(grade2 1.00 to grade5 1.48). `smoothness=bad` costs ×1.35 on natural or gravel
surfaces and ×1.75 elsewhere; worse smoothness costs more, `impassable` is
excluded. `mtb:scale` 1/2/3+ costs ×1.42/×3.0/×8.0 on paths, bridleways,
tracks, footways and cycleways; untagged paths cost ×1.12 unless
`prefer_singletrails` is enabled. Demanding hiking scales are excluded and
`bicycle=dismount` costs ×8.

**Roads and speed.** Quiet local roads stay usable connectors (residential,
unclassified, living street ×1.05, service ×1.08). Tertiary roads cost ×2.2,
secondary ×3.5 and primary ×6.0; trunk roads, motorways, proposed/construction
and abandoned ways are excluded. On unclassified and larger roads a posted
`maxspeed` of 70 adds ×1.15, 80 ×1.3, and 90 or more or `rural` ×1.5 (never on
residential, living-street, service, pedestrian, track, path, cycleway,
footway or bridleway ways); a missing `maxspeed` adds nothing and is not
guessed. BRouter's estimated traffic
class adds ×1.06 to ×1.65 when `consider_traffic_estimate` is on. Ways with
`oneway=reversible` remain routable in both directions at ×1.3.

**Elevation.** BRouter's DEM-based elevation model charges climbs per way.
On mapped natural surfaces (ground, grass, dirt, earth), gravel, fine gravel,
compacted and generic `unpaved` a climb costs `uphillcost` 20 and a descent
`downhillcost` 8; on paved ways, ways without a `surface` tag and loose or
muddy surfaces they cost 55 and 18. Cutoffs are 2 % everywhere. The reduced
cost follows the surface preferences: with `prefer_natural_surfaces` off,
natural surfaces climb at 55/18; with `prefer_gravel_surfaces` off, gravel
surfaces do; `unpaved` (gravel or dirt) keeps 20/8 only while both are on.
Ridge tracks are therefore not traded for valley asphalt on climb cost alone,
while steep paved shortcuts stay penalised; a missing surface tag gets no
relief, and relief is neither sought nor avoided as a goal. A variant without
any climb costs, tested on the pre-tuning profile, was not adopted: it moved
several routes onto steep asphalt (paved share +2.4 points, distance on roads
signed 80 km/h or more 0.50 → 0.91 km, ascent +26 %), although
primary/secondary distance fell (2.43 → 1.98 km). The two costs are assigned
in the way context only, because BRouter treats a global assignment as
read-only there.

**Barriers.** Walls, fences, turnstiles, stiles, spikes and jersey barriers
block a node. Bollards, cycle barriers and kissing gates cost 120, gates and
chains 35, generic or unspecified barriers and chicanes 60, kerbs and cattle
grids 10. Obstacles a rider lifts the bike over (logs, fallen trees, debris,
ditches, hedges, rails, ropes, bars) cost 500: expensive, but not a block.
Steps are blocked unless `allow_steps` is enabled.

The BRF has no region-wide forest cover, route-wide surface percentage,
sound-distance model, loop-diversity constraint or corridor-search state. It
cannot promise a route will meet a global gravel percentage or be a complete
attractive loop; those require logic outside a per-edge BRouter profile.

## Access and directions

Scenic costs and public-route feasibility are separate. Missing bicycle tags
do not make a scenic way less desirable. For feasibility, explicit bicycle
tags take precedence over `vehicle`, which takes precedence over generic
`access`. Explicit `no` and `private` bicycle restrictions are excluded.
Way-level bicycle values that do not establish a supported permission
(including `use_sidepath`) are excluded. Generic `no` and `private` access
restrictions are excluded unless overridden by a more specific
bicycle/vehicle permission. `motorroad=yes` remains excluded unless a bicycle
permission is explicitly tagged. A bicycle permission does not declare that
access is legally valid in every jurisdiction; the profile follows the
encoded OSM access data.

One-way restrictions are directional. `oneway:bicycle=no` permits both
directions. `oneway:bicycle=yes` retains a generic `oneway=-1` direction, or uses
the OSM way direction otherwise. Explicit bicycle-direction tags take priority
over recognized `opposite*` cycleway hints, which permit contraflow only when
no supported bicycle-specific direction is present.
Ordinary roundabouts are treated as one-way unless bicycle direction tags say
otherwise. Turn restrictions and roundabout instructions are enabled. Nodes with
explicit access bans remain blocked even if a bicycle route relation marks
them accessible. Hard barriers such as walls, fences, turnstiles and stiles
are blocked; common gates and bollards have high node costs because their
actual bicycle passability depends on local width and design. Steps are
blocked unless enabled.

OSM tagging can be incomplete, stale or locally interpreted. A profile cannot
verify a permission, barrier opening, trail condition, traffic estimate or
seasonal closure on the ground. Check local rules and the route before riding.

## Scope and validation

The profile is designed for BRouter 1.7.8 through 1.7.10 lookups (lookupversion
10 and 11); brouter.de results are directional only. Synthetic tests and
brouter.de comparisons are not field validation. It is not a standalone navigation app, BRouter deployment, map overlay, or field
validated route recommender. It does not consume human GPX tracks. Its
preferences are hypotheses encoded as routing costs; actual route choice also
depends on graph coverage, BRouter settings and the available map data.

The profile source is original and uses BRouter's documented BRF interface
and stock lookup names. No third-party gravel profile is bundled or copied.
The original profile and accompanying tooling are MIT licensed; see `LICENSE`.
