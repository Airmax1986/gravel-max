# Third-party components and data

The gravel-max BRF, validation tooling and accompanying documentation are
original project code released under the MIT license in `LICENSE`. This license
does not replace the separate licenses of dependencies or external map data.

The validation runner compiles an independently downloaded BRouter v1.7.8
checkout (commit `041488b36c761ff2dd0fd71c1538b646364223ea`). BRouter is MIT
licensed, copyright BRouter contributors. Its source and license remain in that
checkout: https://github.com/abrensch/brouter/blob/v1.7.8/LICENSE

Test-only map-building dependencies, downloaded separately and not included in
the source release:

- Osmosis OSM Binary 0.48.3, LGPL-3.0, from Maven Central.
- Protocol Buffers Java 3.12.2, BSD-3-Clause, from Maven Central.
- JDK 17 supplied by the developer or CI environment under its own license.

The synthetic test graph is created from coordinates and tags in the original
Java test fixture; it does not use user GPX or copy a real OSM extract. Public
routes generated with real OpenStreetMap data require appropriate OSM attribution
and data-license compliance by the consuming application. This source release
does not redistribute OSM databases, elevation tiles, or personal route data.

The Bikerouter application and m11n overlay were inspected as integration
references. Their application source, profiles and map styles are not bundled
or relicensed as gravel-max.
