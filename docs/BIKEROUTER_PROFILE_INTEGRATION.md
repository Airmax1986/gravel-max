# Using gravel-max with bikerouter.de and the Bikerouter app

Two different projects share the name. [bikerouter.de](https://bikerouter.de)
is a BRouter-Web site where anyone can load a custom profile in the browser.
The Bikerouter / Wegwiesel app (`boonkerz/bikerouter`) is a separate Flutter
application whose profiles are installed by its server operator; the sections
after this one cover that app.

## bikerouter.de (and other BRouter-Web sites)

gravel-max is not in bikerouter.de's profile list. Use it as a custom profile:

1. Open [bikerouter.de](https://bikerouter.de) and the profile editor in the
   sidebar.
2. Replace the editor text with the full contents of `profiles/gravel-max.brf`
   and press **Upload**. The site sends the profile to its routing engine,
   which answers with a temporary `custom_…` profile id, and routes with it
   from then on.
3. Plan the route as usual. The browser keeps the profile text; the engine may
   drop uploaded profiles after a while, so upload again if routing reports a
   missing profile.

Checked on 2026-10-28: bikerouter.de's engine (`BRouter-1.7.9`) accepted the
upload and returned a route between Bad Iburg and Hilter. The profile declares
its options (see the table below) in BRouter-Web's `%name% | label | boolean`
format, so the editor should offer them as switches; that part was not checked
in the browser. Adding gravel-max to the site's built-in profile list is up to
the bikerouter.de operator.

The same steps work on other BRouter-Web instances that allow custom profiles,
such as brouter.de.

## Bikerouter / Wegwiesel app

Integration examined against `boonkerz/bikerouter` commit
`527f4c28a9607e2e95819fbfddd4c61302e6edaf`:
https://github.com/boonkerz/bikerouter/tree/527f4c28a9607e2e95819fbfddd4c61302e6edaf

### Server installation

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

### App registration

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

### Offline and iPhone boundary

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
