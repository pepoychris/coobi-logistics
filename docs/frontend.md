# Coobi Logistics - Vue dashboard (MVP-7)

`frontend/` is the public view of the stack: a Vue 3 application that reads the REST API of
MVP-5 and the Server-Sent Events streams of MVP-6, and shows the fleet on a map, the live
KPIs of the pipeline and the events it stores as they happen. It is a reader only - it has no
write path, no second copy of the state and no number of its own.

Stack: Vue 3 with TypeScript, built by Vite, with Leaflet and OpenStreetMap tiles for the map.
No UI framework and no paid map provider are involved.

## Running it

In the stack there is nothing to install: `docker compose up --build` builds this dashboard into
an nginx container that serves the bundle on <http://localhost:5173> and proxies `/api` to the
API of the stack, so the page reaches the API of its own origin exactly as it does in
development. That is the containerized server of MVP-10, documented in
[deployment.md](deployment.md).

To work on the dashboard itself, Node.js 20.19 or newer and npm are the only requirements.

```powershell
cd frontend
npm install
npm run dev
```

```bash
cd frontend
npm install
npm run dev
```

The dev server listens on <http://localhost:5173> and proxies `/api` to
`http://localhost:8082`, where the logistics API of the local stack answers. Start the stack
as described in [README.md](../README.md) first - the dashboard is a view of it and shows a
disconnected state, with no invented values, while the API is not there.

| Command | What it does |
| --- | --- |
| `npm run dev` | Dev server with hot reload on port `5173`, proxying `/api` to the API |
| `npm run build` | Type-checks the project with `vue-tsc` and writes the production bundle to `dist/` |
| `npm run preview` | Serves the built bundle on port `4173` with the same `/api` proxy |
| `npm run typecheck` | Type-checks without building |
| `npm test` | Runs the unit and component tests with Vitest in a `jsdom` environment |

## Configuration

`.env.example` in this directory is the source of truth for the environment contract of the
frontend; the root `.env.example` points at it. Vite reads these values at build time, so they
are baked into the bundle and must never hold a secret, and changing one requires a rebuild.

| Variable | Default | Scope | Meaning |
| --- | --- | --- | --- |
| `VITE_API_BASE_URL` | empty | Frontend build | Origin of the API. Empty means "the origin that served the dashboard", so the app calls `/api/v1/...` on itself |
| `VITE_API_PROXY_TARGET` | `http://localhost:8082` | Dev and preview servers | Origin those two servers proxy `/api` to. It never reaches the browser bundle |
| `API_PROXY_TARGET` | `http://logistics-api:8082` | Dashboard container | Origin the nginx server of the container proxies `/api` to. It is a runtime value of the container, substituted into its configuration when it starts, and it lives in the root `.env` rather than in this directory |

The container is built with the same-origin default and never reads this directory's `.env`, so
the two `VITE_*` values above are the ones a development server uses. An absolute
`VITE_API_BASE_URL` baked into a bundle would make the whole containerized setup depend on CORS
being enabled on the API, which is why the image keeps the same-origin path and the proxy target
is the knob that moves.

### Why the default is a same-origin path

The API of MVP-5 and MVP-6 ships no CORS configuration, and that is deliberate: it is a
service that answers its own origin, not a public API for arbitrary pages. A browser that
loaded the dashboard from `http://localhost:5173` and called `http://localhost:8082` directly
would therefore be blocked by the browser itself, not by the service.

The two servers that matter locally solve that with the proxy above: the page is served from
the origin it talks to, `EventSource` sees a same-origin stream, and nothing has to be relaxed
on the API to make the local demo work.

An absolute URL is still supported for a deployment that puts the API somewhere else, with the
one condition that the browser is allowed to read it:

```text
VITE_API_BASE_URL=https://api.example.com
```

That works when a reverse proxy serves both the dashboard and `/api` from one origin, or when
CORS on the API allows the origin of the dashboard. Serving the built `dist/` from the same
origin as the API needs neither.

## What it shows

**Live KPIs (MVP-7.2).** `Events processed`, `Events per second`, `Active vehicles`,
`Alerts generated` and `System status`, all of them read from `GET /api/v1/statistics` and from
the `statistics` event of `GET /api/v1/stream/statistics`. The page paints once from REST - so
the first impression is not an empty one - and then follows the stream, which re-sends the same
object every interval the API is configured with (`COOBI_STREAM_STATISTICS_INTERVAL`, `1s` by
default).

Every card names the source of its value, and a value the API reports as `null` is drawn as
`—` rather than as a zero: `processedEvents` and `eventsPerSecond` are `null` exactly when the
Kafka Streams counter of the processor cannot be read, and a dashboard that printed `0` there
would be inventing a measurement nobody took (MVP-5.4).

**Fleet map (MVP-7.3).** Leaflet over OpenStreetMap tiles - no key, no paid provider, and the
attribution the tile service requires is rendered with the map. Vehicles come from two places
that are the same source of truth: the `vehicle` events of `GET /api/v1/stream/events`, and one
page of `GET /api/v1/vehicles` used to seed the view and then re-read every 30 seconds so a
sampled stream cannot leave the map drifting.

Markers are keyed by `vehicleId` and updated in place: a marker is created the first time a
vehicle is seen, moved and re-drawn only when a value it draws changed, and removed when the
backend stops reporting it. The map itself is created once and never rebuilt. Clicking a marker
opens its `vehicleId`, `speed`, `status` and `lastUpdate`.

**Live event feed (MVP-7.4).** The `alert` and `vehicle` events of the event stream, oldest
first, each row carrying the time of the event, the vehicle, the kind of event and - for an
alert - the measured speed and the threshold that was crossed. Alerts are set apart from
telemetry both by their label and by the styling of the row, so a speeding vehicle is not
something a reader has to hunt for.

The feed holds at most 100 entries and drops the oldest when it is full
(`FEED_CAPACITY` in `src/composables/useEventStream.ts`), and the reader can stop following the
live edge or clear the view. What the browser holds is therefore a window on the stream and not
a growing transcript of the pipeline.

## Connection states

Both streams are the same kind of connection, and both are described by the same four states:

| State | When | What the page shows |
| --- | --- | --- |
| Connecting | Nothing received yet, not even the opening frame | Placeholders in the KPI cards, an empty feed, the map without markers |
| Live | The connection is open and frames are arriving | The values of the stream, with the age of the last frame |
| Reconnecting | The connection dropped and the browser is retrying | The last known values, marked as not live, with a "Reconnect" action |
| Disconnected | The connection is closed for good: the API refused it (`503` has its own meaning at capacity), the browser gave up, or it has no `EventSource` | A banner naming the API URL the page is reading, and no values that pretend otherwise |

`Retry: 3000` is sent by the API on every connection, so a stream that ends because an
intermediary closed it, or because the service restarted, is reopened by the browser on its
own; the last values stay on screen, and the state says that they are not live.

## How the frontend is kept honest

- Nothing is computed on the client. No rate is derived, no counter is incremented, no alert is
  deduced: the page draws what arrived.
- A frame that is not the JSON of the contract is dropped, and the connection it arrived on is
  left alone.
- The feed is bounded, one marker exists per vehicle id, and there are exactly two connections
  to the API per page - the two streams - no matter how many components read them.
- The map is created once; telemetry moves markers and never re-lays the map out.

## Tests

```powershell
cd frontend
npm test
```

The suite is deterministic and offline: `fetch` and `EventSource` are replaced by stand-ins, so
a stream is driven frame by frame and the assertions are about behaviour rather than timing.
It covers the mapping from a stream frame to a feed row, the incremental rule of the marker
layer, the popup built for a vehicle, the two composables (including the bounded feed, the
connection states and the periodic reconcile), and the three components that carry the
acceptance criteria of this milestone.

## Troubleshooting

| Symptom | Cause and what to do |
| --- | --- |
| The header says *Disconnected* | The API is not answering on the origin the page reads. The banner prints that origin; start the stack, or set `VITE_API_BASE_URL` |
| The KPIs show `—` | The API answered, but the Kafka Streams counter of the processor could not be read. The status card and the stream are the places to look, and `processedEvents` staying `null` is the documented behaviour |
| The map is empty while the KPIs move | No vehicle state has arrived yet. The stream sends at most `COOBI_STREAM_EVENTS_MAX_VEHICLES_PER_POLL` per tick and samples a burst, so a busy pipeline still arrives at a reader's pace |
| The feed stays empty | Same reason: the feed is fed by the sampled stream. The history is `GET /api/v1/alerts` |
| Tiles do not load | The tile service of OpenStreetMap is unreachable, usually an offline machine or a blocked host. Markers and values are unaffected |
| The dashboard shows fewer events than the pipeline processed | Expected, and documented in [docs/logistics-api.md](logistics-api.md): the stream is a bounded sample, not a pipe |

## Non-goals

Authentication, routing, a state library, server-side rendering, charts beyond the KPI cards and
the map, and any second copy of the pipeline state.
