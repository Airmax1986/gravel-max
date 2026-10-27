# Using gravel-max with Bikerouter / Wegwiesel

Integration examined against `boonkerz/bikerouter` commit
`527f4c28a9607e2e95819fbfddd4c61302e6edaf`:
https://github.com/boonkerz/bikerouter/tree/527f4c28a9607e2e95819fbfddd4c61302e6edaf

## Server installation

The upstream `Dockerfile.brouter` builds BRouter v1.7.8. On startup it copies
`/brouter/customprofiles/*.brf` to `/brouter/profiles2/`. Install this project's
`profiles/gravel-max.brf` in the operator's mounted custom-profile directory and
restart that operator-controlled service through its normal deployment workflow.
Do not overwrite `lookups.dat`: it must match the routing segments.

The upstream production compose file uses `/opt/wegwiesel/customprofiles` on its
host. This is an upstream example, not a server to which this release deploys.

Validate against the operator's endpoint before enabling the app selector:

```sh
curl --get 'https://YOUR-SERVER/brouter' \
  --data-urlencode 'lonlats=8.72,50.0025|8.7237,49.9975' \
  --data-urlencode 'profile=gravel-max' \
  --data-urlencode 'format=geojson'
```

Use points covered by that server's segments. A missing-profile response means
the server installation is incomplete. Do not silently substitute another
profile and label its result gravel-max.

## App registration

For an operator-maintained build of the linked Flutter app, add an entry to
`app/lib/models/profile.dart` in the `profiles` registry:

```dart
BikeProfile(
  id: 'gravel-max',
  category: ProfileCategory.gravel,
  icon: '🪨',
  avgSpeedKmh: 20,
),
```

The speed is an initial UI estimate, not measured gravel-max performance. Add a
localized display name following the app's existing localization process.
`BRouterService` passes the profile ID in its HTTP request. Its native default
endpoint is `https://wegwiesel.app/brouter`; the operator must either install
the profile there or configure their build to use their own server.

The app's pre-existing routing-option toggles may send different parameter names
from gravel-max. Expose only options documented in the profile guide or explicitly
map the app's toggles to those variables. Do not imply that unsupported toggles
affect this profile.

Options the app may expose (declared with `%name%` annotations in the BRF and
sent as `profile:<name>=1|0`):

| Option | Default |
| --- | --- |
| `prefer_natural_surfaces` | `true` |
| `prefer_gravel_surfaces` | `true` |
| `prefer_singletrails` | `false` |
| `consider_traffic_estimate` | `true` |
| `allow_steps` | `false` |
| `allow_ferries` | `true` |
| `consider_noise` | `false` |
| `consider_forest` | `false` |

## Offline and iPhone boundary

The upstream offline-routing documentation describes a hardcoded trekking cost
model, not a general BRF interpreter. A custom server BRF does not install a
native offline algorithm. At the inspected revision, `GraphOfflineRouter`
rejects unsupported profiles with `OfflineRoutingException`, and
`BRouterService.calculateRoute` catches that exception and uses its online
server. Preserve this behavior; do not return trekking results as gravel-max.

This release supplies the profile and integration instructions. It does not
modify or deploy the upstream application, sign an iOS build, or assert that a
physical iPhone has passed testing. Before shipping an app build, verify selector
behavior, outgoing profile ID, route display, options, GPX export and offline
failure/fallback on the actual target app.

No map-overlay changes are required: map rendering and routing costs are
independent interfaces.
