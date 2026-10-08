# Geospatial Property Intelligence

Deterministic, map-SDK-independent geo layer for target properties and comparables.

Package: `com.example.domain.geo` (pure Kotlin, no Android/Compose/map SDK types).

## Components

| File | Responsibility |
| --- | --- |
| `GeoPrimitives.kt` | `GeoPoint` (validated WGS84 coordinate, haversine distance in **statute miles**, initial bearing), `GeoBoundingBox` (fit-to-points, radius box, expansion, antimeridian-aware containment), `Geo` constants |
| `MapPin.kt` | `MapPin` / `MapPinRole` (TARGET, COMPARABLE, PROPERTY) / `MapPinEmphasis` / `MapPinDistance` — renderer-agnostic pin metadata |
| `GeoQueryEngine.kt` | Radius filtering, nearest-N, bounding-box queries, comparables-for-a-target |
| `GeoClusterer.kt` | Deterministic grid clustering + distance-band grouping of comps |
| `MapViewport.kt` | `MapViewport` + `ViewportFitter` (Web-Mercator fit of pins/bounds/radius to a pixel viewport, zoom in the standard XYZ tile convention) |
| `PropertyGeoAdapter.kt` | Maps `PropertyEntity` rows to `MapPin`s (read-only; no DB/scoring changes) |
| `PropertyMapIntelligence.kt` | Composes the above into a `PropertyMapScene` (target, comps, clusters, bands, bounds, viewport) |

## Guarantees

- **Deterministic ordering.** Distance results are ordered by ascending distance, ties broken by
  pin id; bounds/pin lists are ordered by id. Output never depends on input iteration order.
- **No fabricated geography.** Invalid, non-finite, out-of-range and `(0,0)` "null island"
  coordinates yield `null` instead of a default location. Empty datasets yield empty results and a
  `null` viewport — callers decide what to show.
- **Edge cases handled.** Poles, the ±180° antimeridian, degenerate (single-point) bounds, zero
  radius, inclusive radius boundary, oversized limits.
- **No map SDK.** Rendering stays in the existing UI layer; this task adds no SDK dependency and
  leaves `PropertyMapCanvas` untouched.

## Tests

`app/src/test/java/com/example/domain/geo/` — geographic calculations against published US city
coordinates, radius filtering, ordering/determinism, empty datasets, coordinate edge cases,
clustering, viewport fitting and the property adapter.
