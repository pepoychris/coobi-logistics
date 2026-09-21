/**
 * A scripted stand-in for the logistics API, used only to record the demo.
 *
 * The dashboard is a reader: it takes its fleet, its statistics and its events
 * from `/api/v1`, and it cannot tell this server from the real one because the
 * contract is the same. That is what makes it useful for a recording - the GIF
 * shows the real dashboard rendering a scripted fleet, not a mock-up of the
 * screen, and no benchmark number of the project is claimed by anything here.
 *
 * Everything is deterministic: the fleet, the positions and the alerts come
 * from a seeded generator, so recording the demo twice produces the same
 * animation.
 *
 * Usage: node scripts/demo/server.mjs [--port 5199] [--vehicles 140]
 */

import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { extname, join, normalize, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

/** Where the built dashboard is served from. */
const DIST = resolve(fileURLToPath(new URL('../../dist', import.meta.url)))

/** The reference point of the scripted fleet: the centre of the district. */
const ORIGIN = { latitude: 39.4699, longitude: -0.3763 }

/** How many metres one degree is, at that latitude. */
const METERS_PER_DEGREE = 111_320
const METERS_PER_DEGREE_LONGITUDE = METERS_PER_DEGREE * Math.cos((ORIGIN.latitude * Math.PI) / 180)

/** The speed limit the scripted alerts use, as the stream processor documents it. */
const SPEED_LIMIT = 120

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.map': 'application/json; charset=utf-8',
}

/** A deterministic generator, so the recording is the same every time. */
function mulberry32(seed) {
  let state = seed >>> 0
  return () => {
    state = (state + 0x6d2b79f5) >>> 0
    let value = Math.imul(state ^ (state >>> 15), 1 | state)
    value = (value + Math.imul(value ^ (value >>> 7), 61 | value)) ^ value
    return ((value ^ (value >>> 14)) >>> 0) / 4_294_967_296
  }
}

/**
 * The scripted fleet: vans that drive around the district, in metres, and the
 * telemetry of each one.
 *
 * They move a little on every tick, some of them occasionally break the speed
 * limit and one of them stops for good, which is what makes the feed of the
 * recording show both an alert and ordinary telemetry.
 */
class ScriptedFleet {
  constructor(count, seed = 20260101) {
    this.random = mulberry32(seed)
    this.vehicles = new Map()
    for (let index = 0; index < count; index += 1) {
      const heading = this.random() * 360
      const x = (this.random() - 0.5) * 14_000
      const y = (this.random() - 0.5) * 10_000
      // Roughly one vehicle in eight is parked, so the map of the recording
      // shows both of the movement states the legend names.
      const parked = this.random() < 0.13
      this.vehicles.set(`TRUCK-${String(index + 1).padStart(5, '0')}`, {
        vehicleId: `TRUCK-${String(index + 1).padStart(5, '0')}`,
        x,
        y,
        heading,
        speed: parked ? 0 : 30 + this.random() * 50,
        status: parked ? 'STOPPED' : 'MOVING',
        parked,
        lastUpdate: new Date().toISOString(),
      })
    }
  }

  /** Advances every vehicle by one tick of `seconds`, and returns the ones that moved. */
  step(seconds) {
    const moved = []
    let index = 0
    for (const entry of this.vehicles.values()) {
      index += 1
      entry.heading = (entry.heading + (this.random() - 0.5) * 9 + 360) % 360
      if (entry.parked) {
        entry.speed = 0
        entry.status = 'STOPPED'
      } else {
        const surge = this.random() < 0.02 ? 45 : 0
        entry.speed = Math.min(150, Math.max(18, entry.speed + (this.random() - 0.5) * 22 + surge))
        entry.status = 'MOVING'
      }
      const distance = (entry.speed / 3.6) * seconds
      const radians = (entry.heading * Math.PI) / 180
      entry.x += Math.sin(radians) * distance
      entry.y += Math.cos(radians) * distance
      entry.lastUpdate = new Date().toISOString()
      moved.push(entry)
    }
    return moved
  }

  /** The latest state of one vehicle, as the version-1 contract reports it. */
  reported(entry) {
    return {
      vehicleId: entry.vehicleId,
      latitude: ORIGIN.latitude + entry.y / METERS_PER_DEGREE,
      longitude: ORIGIN.longitude + entry.x / METERS_PER_DEGREE_LONGITUDE,
      speed: Number(entry.speed.toFixed(2)),
      heading: Number(entry.heading.toFixed(1)),
      status: entry.status,
      lastUpdate: entry.lastUpdate,
      updatedAt: entry.lastUpdate,
    }
  }

  /** One page of the fleet, newest telemetry first, as `/api/v1/vehicles` answers. */
  page(size) {
    const all = [...this.vehicles.values()].map((entry) => this.reported(entry))
    return {
      content: all.slice(0, size),
      page: 0,
      size,
      totalElements: all.length,
      totalPages: 1,
      first: true,
      last: true,
    }
  }
}

/** The state of one recording session, kept per connection. */
class ScriptedPipeline {
  constructor(fleet, options) {
    this.fleet = fleet
    this.alerts = []
    this.alertsGenerated = 0
    this.processedEvents = 4_812_000
    this.startedAt = Date.now()
    this.tickIntervalMs = options.tickIntervalMs
    this.alertEveryTicks = options.alertEveryTicks
    this.subscribers = new Set()
    this.tick = 0
    this.timer = null
  }

  /** The statistics the dashboard polls once, so the first paint is not empty. */
  statistics() {
    const elapsed = Math.max(1, (Date.now() - this.startedAt) / 1_000)
    return {
      processedEvents: this.processedEvents,
      eventsPerSecond: Math.round(1_140 + Math.sin(elapsed / 3) * 40),
      activeVehicles: [...this.fleet.vehicles.values()].filter((entry) => entry.status === 'MOVING').length,
      alertsGenerated: this.alertsGenerated,
      uptimeSeconds: Math.round(7_900 + elapsed),
    }
  }

  /** Adds one subscriber, and starts the ticker while there is one. */
  subscribe(send) {
    this.subscribers.add(send)
    if (this.timer === null) {
      this.timer = setInterval(() => this.advance(), this.tickIntervalMs)
    }
    return () => {
      this.subscribers.delete(send)
      if (this.subscribers.size === 0 && this.timer !== null) {
        clearInterval(this.timer)
        this.timer = null
      }
    }
  }

  /** One tick of the scripted pipeline: vehicles move, and now and then an alert appears. */
  advance() {
    this.tick += 1
    const seconds = this.tickIntervalMs / 1_000
    const moved = this.fleet.step(seconds)
    this.processedEvents += Math.round(1_140 * seconds)

    // The stream is a sample on purpose, exactly as the API documents it: a
    // handful of vehicle states per tick, not the whole fleet.
    const vehicles = []
    const stride = Math.max(1, Math.floor(moved.length / 8))
    for (let index = 0; index < moved.length; index += stride) {
      vehicles.push(this.fleet.reported(moved[index]))
    }

    for (const entry of moved) {
      if (entry.speed > SPEED_LIMIT && this.tick % this.alertEveryTicks === 0 && this.alerts.length < 40) {
        this.alertsGenerated += 1
        this.alerts.push({
          id: this.alertsGenerated,
          eventId: `demo-${this.alertsGenerated}`,
          vehicleId: entry.vehicleId,
          type: 'SPEEDING_DETECTED',
          severity: 'WARNING',
          occurredAt: entry.lastUpdate,
          createdAt: entry.lastUpdate,
          metadata: { speed: Number(entry.speed.toFixed(1)), threshold: SPEED_LIMIT },
        })
      }
    }

    const alerts = []
    if (this.tick % this.alertEveryTicks === 0 && this.alerts.length > 0) {
      alerts.push(this.alerts[this.alerts.length - 1])
    } else if (this.tick % this.alertEveryTicks === 1 && this.alerts.length > 0) {
      alerts.push(this.alerts[Math.max(0, this.alerts.length - 2)])
    }

    this.broadcast(vehicles, alerts)
  }

  /** Sends one tick to every subscriber, in the frame shape of the API. */
  broadcast(vehicles, alerts) {
    for (const send of this.subscribers) {
      for (const alert of alerts) {
        send('alert', alert)
      }
      for (const vehicle of vehicles) {
        send('vehicle', vehicle)
      }
    }
  }
}

/** The frame format of the API of MVP-6: `event:` name, then `data:` as JSON. */
function sseFrame(event, payload, id) {
  return `id: ${id}\nevent: ${event}\ndata: ${JSON.stringify(payload)}\n\n`
}

/** Serves one file out of the built bundle. */
async function serveStatic(response, pathname) {
  const relative = normalize(pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, ''))
  if (relative.startsWith('..')) {
    response.writeHead(403).end('forbidden')
    return
  }
  try {
    const body = await readFile(join(DIST, relative))
    response.writeHead(200, { 'content-type': MIME[extname(relative)] ?? 'application/octet-stream' })
    response.end(body)
  } catch {
    const body = await readFile(join(DIST, 'index.html'))
    response.writeHead(200, { 'content-type': MIME['.html'] })
    response.end(body)
  }
}

export function createDemoServer(options = {}) {
  const fleet = new ScriptedFleet(options.vehicles ?? 140, options.seed ?? 20260101)
  const pipeline = new ScriptedPipeline(fleet, {
    tickIntervalMs: options.tickIntervalMs ?? 250,
    alertEveryTicks: options.alertEveryTicks ?? 7,
  })

  const server = createServer(async (request, response) => {
    const url = new URL(request.url, 'http://localhost')
    const path = url.pathname

    if (path === '/api/v1/statistics') {
      response.writeHead(200, { 'content-type': MIME['.json'] })
      response.end(JSON.stringify(pipeline.statistics()))
      return
    }

    if (path === '/api/v1/vehicles') {
      const size = Number(url.searchParams.get('size') ?? 100)
      response.writeHead(200, { 'content-type': MIME['.json'] })
      response.end(JSON.stringify(fleet.page(Number.isFinite(size) ? size : 100)))
      return
    }

    if (path === '/api/v1/alerts') {
      response.writeHead(200, { 'content-type': MIME['.json'] })
      response.end(
        JSON.stringify({
          content: pipeline.alerts.slice(-20),
          page: 0,
          size: 20,
          totalElements: pipeline.alerts.length,
          totalPages: 1,
          first: true,
          last: true,
        }),
      )
      return
    }

    if (path === '/api/v1/stream/statistics' || path === '/api/v1/stream/events') {
      response.writeHead(200, {
        'content-type': 'text/event-stream; charset=utf-8',
        'cache-control': 'no-cache, no-transform',
        connection: 'keep-alive',
      })
      response.write('retry:3000\n\n')
      let id = 0
      const interval = path.endsWith('/statistics') ? 1_000 : pipeline.tickIntervalMs
      const timer = setInterval(() => {
        id += 1
        response.write(
          path.endsWith('/statistics')
            ? sseFrame('statistics', pipeline.statistics(), id)
            : `: tick ${id}\n\n`,
        )
      }, interval)
      const unsubscribe = pipeline.subscribe((event, payload) => {
        id += 1
        response.write(sseFrame(event, payload, id))
      })
      const close = () => {
        clearInterval(timer)
        unsubscribe()
        response.end()
      }
      request.on('close', close)
      return
    }

    if (request.method !== 'GET') {
      response.writeHead(405, { 'content-type': MIME['.json'] })
      response.end(JSON.stringify({ detail: 'this scripted API only answers GET' }))
      return
    }

    await serveStatic(response, path)
  })

  return { server, fleet, pipeline }
}

/** Starts the demo API and resolves with the port it listens on. */
export async function startDemoServer(options = {}) {
  const { server, fleet, pipeline } = createDemoServer(options)
  const port = options.port ?? 0
  await new Promise((done) => server.listen(port, '127.0.0.1', done))
  return { server, fleet, pipeline, port: server.address().port }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const port = Number(process.env.DEMO_PORT ?? 5199)
  const { port: listening } = await startDemoServer({ port, vehicles: Number(process.env.DEMO_VEHICLES ?? 140) })
  console.log(`The scripted demo API is listening on http://127.0.0.1:${listening}`)
}
