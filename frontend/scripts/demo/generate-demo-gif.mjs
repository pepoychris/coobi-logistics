/**
 * Records the dashboard into the GIF the README shows.
 *
 * The recording is of the real thing: the built bundle is served to a real
 * Chromium, the scripted stand-in of `server.mjs` answers the same `/api/v1`
 * contract as the API, and what ends up in the file is a sequence of
 * screenshots of the page a reader would open. Nothing here measures the
 * project, and the caption of the artefact says so.
 *
 * It needs a browser and nothing else: the GIF is encoded in process, because
 * the ffmpeg that ships with Playwright is a stripped build that knows no PNG
 * decoder and no GIF muxer.
 *
 *   PLAYWRIGHT_MODULE_DIR  a directory that holds a `playwright` package
 *   PLAYWRIGHT_CHROMIUM    the browser executable to drive, when the default is not wanted
 *
 * Usage: npm run demo:gif
 */

import { existsSync, readdirSync } from 'node:fs'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

import { countColours, decodePng } from './png.mjs'
import { composite, decodeFrames, encodeGif, resize } from './gif.mjs'
import { startDemoServer } from './server.mjs'

const require = createRequire(import.meta.url)

/** Where the recording is written. */
const OUTPUT = resolve(fileURLToPath(new URL('../../../docs/assets/coobi-fleet-demo.gif', import.meta.url)))

/** The size of the page the GIF shows, in CSS pixels. */
const VIEWPORT = { width: 1180, height: 760 }

/** The width of the GIF itself; the height follows the shape of the page. */
const GIF_WIDTH = 900

/** How long the recording runs, how often a frame is captured, and its colours. */
const FRAME_COUNT = 40
const FRAME_INTERVAL_MS = 200
const MAX_COLOURS = 128

/** How many vehicles the scripted fleet drives. */
const VEHICLES = 140

/** The places a `playwright` package may live on this machine. */
function playwrightCandidates() {
  const candidates = []
  if (process.env.PLAYWRIGHT_MODULE_DIR) {
    candidates.push(process.env.PLAYWRIGHT_MODULE_DIR)
  }
  candidates.push(process.cwd(), resolve(process.cwd(), 'node_modules'))

  const home = process.env.USERPROFILE ?? process.env.HOME ?? ''
  if (home) {
    candidates.push(
      join(home, '.cache', 'codex-runtimes', 'codex-primary-runtime', 'dependencies', 'node', 'node_modules'),
    )
    candidates.push(join(home, '.codex', 'vendor_imports'))
  }
  return candidates
}

/**
 * Loads playwright from wherever it is installed, and says so when it is
 * nowhere.
 *
 * The package is CommonJS, so its exports may arrive either on the namespace or
 * behind `default`, depending on the Node version that loads it.
 */
async function loadPlaywright() {
  const paths = playwrightCandidates().filter((candidate) => candidate && existsSync(candidate))
  try {
    const resolved = require.resolve('playwright', { paths })
    const loaded = await import(pathToFileURL(resolved).href)
    const module = loaded.chromium ? loaded : loaded.default
    if (!module?.chromium) {
      throw new Error('the playwright package was loaded but exposes no chromium')
    }
    return module
  } catch (failure) {
    throw new Error(
      `playwright could not be found. Point PLAYWRIGHT_MODULE_DIR at a directory that holds it. (${failure.message})`,
    )
  }
}

/** The browser to drive: the one Playwright installed, unless one is named. */
function chromiumExecutable() {
  if (process.env.PLAYWRIGHT_CHROMIUM) {
    return process.env.PLAYWRIGHT_CHROMIUM
  }
  const home = process.env.LOCALAPPDATA ?? process.env.USERPROFILE ?? ''
  const roots = [join(home, 'ms-playwright'), join(process.env.USERPROFILE ?? '', '.cache', 'ms-playwright')].filter(
    (root) => root && existsSync(root),
  )
  for (const root of roots) {
    for (const entry of readdirSync(root)) {
      const candidate = join(root, entry, 'chrome-win64', 'chrome.exe')
      if (existsSync(candidate)) {
        return candidate
      }
    }
  }
  return undefined
}

/**
 * Screenshots the map and counts the colours of the result.
 *
 * A WebGL canvas cannot be read back once its frame has been composited, so the
 * check is made on the screenshot itself - which is exactly what the GIF is
 * made of, and therefore a stronger claim than reading the canvas would be.
 */
async function mapColourCount(page) {
  const box = await page.locator('.fleet-map').boundingBox()
  if (!box) {
    throw new Error('the map panel is not on the page')
  }
  const shot = await page.screenshot({
    clip: { x: box.x, y: box.y, width: Math.min(box.width, 640), height: Math.min(box.height, 420) },
  })
  return countColours(decodePng(shot))
}

/**
 * The average colour of a frame, which is what a recording is checked against.
 *
 * A palette of 128 colours cannot reproduce a screenshot exactly, but it cannot
 * move its average either. A stream that desynchronises, on the other hand,
 * moves it a long way - which is what makes this a check and not a formality.
 */
function meanColour(frame) {
  let red = 0
  let green = 0
  let blue = 0
  const pixels = frame.pixels.length / 4
  for (let offset = 0; offset < frame.pixels.length; offset += 4) {
    red += frame.pixels[offset]
    green += frame.pixels[offset + 1]
    blue += frame.pixels[offset + 2]
  }
  return [red / pixels, green / pixels, blue / pixels]
}

/**
 * Reads the recording back and compares every frame of it with the screenshot
 * that frame was made from, then asks a browser to read one frame of its own.
 *
 * The file is only correct if a decoder accepts it, and its pixels are only
 * correct if what a decoder draws is what was captured - so the comparison is
 * made on the frames as they are composited for a reader, transparent deltas
 * and all. The browser is the last word because it is a decoder this repository
 * did not write.
 */
async function checkRecording(browser, frames, planes) {
  const recording = decodeFrames(await readFile(OUTPUT))
  const images = composite(recording)
  const captured = frames.map(meanColour)
  // The indices the file carries have to be the indices that were written, to
  // the pixel: a code that widens one code too early, a delta frame that drops
  // a change it should have carried and a sub-block split in the wrong place
  // all show up here and nowhere else.
  const wrongFrames = planes.filter((plane, index) => {
    const decoded = recording.frames[index]?.indices
    return !decoded || Buffer.compare(Buffer.from(decoded), Buffer.from(plane)) !== 0
  })
  let worst = 0
  let worstFrame = 0
  images.forEach((image, index) => {
    const mean = meanColour(image)
    const difference = Math.max(...mean.map((value, channel) => Math.abs(value - captured[index][channel])))
    if (difference > worst) {
      worst = difference
      worstFrame = index
    }
  })

  const page = await browser.newPage({ viewport: { width: 920, height: 620 } })
  let read
  try {
    await page.goto(pathToFileURL(OUTPUT).href)
    await page.waitForFunction(() => {
      const image = document.querySelector('img')
      return Boolean(image && image.complete && image.naturalWidth > 0)
    })
    read = await page.evaluate(() => {
      const image = document.querySelector('img')
      const canvas = document.createElement('canvas')
      canvas.width = image.naturalWidth
      canvas.height = image.naturalHeight
      const context = canvas.getContext('2d', { willReadFrequently: true })
      context.drawImage(image, 0, 0)
      const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data
      const colours = new Set()
      let red = 0
      let green = 0
      let blue = 0
      for (let pixel = 0; pixel < canvas.width * canvas.height; pixel += 1) {
        const offset = pixel * 4
        red += pixels[offset]
        green += pixels[offset + 1]
        blue += pixels[offset + 2]
        if (pixel % 7 === 0) {
          colours.add((pixels[offset] << 16) | (pixels[offset + 1] << 8) | pixels[offset + 2])
        }
      }
      const count = canvas.width * canvas.height
      return {
        width: canvas.width,
        height: canvas.height,
        colours: colours.size,
        mean: [red / count, green / count, blue / count],
      }
    })
  } finally {
    await page.close()
  }

  const closest = Math.min(
    ...images.map((image) => {
      const mean = meanColour(image)
      return Math.max(...mean.map((value, channel) => Math.abs(value - read.mean[channel])))
    }),
  )
  return {
    width: recording.width,
    height: recording.height,
    frames: images.length,
    wrongFrames: planes.length - wrongFrames.length,
    expectedFrames: planes.length,
    colours: read.colours,
    worst,
    worstFrame,
    closest,
  }
}

async function main() {
  const { chromium } = await loadPlaywright()
  const demo = await startDemoServer({ vehicles: VEHICLES })
  const browser = await chromium.launch({
    executablePath: chromiumExecutable(),
    args: ['--enable-unsafe-swiftshader', '--use-gl=angle', '--use-angle=swiftshader'],
  })

  try {
    const page = await browser.newPage({ viewport: VIEWPORT, deviceScaleFactor: 1 })
    await page.goto(`http://127.0.0.1:${demo.port}/`, { waitUntil: 'load' })
    await page.waitForSelector('.fleet-map__canvas', { timeout: 20_000 })
    await page.waitForFunction(() => document.querySelector('.chip--budget')?.textContent?.includes('drawn in full'), {
      timeout: 20_000,
    })
    await page.waitForTimeout(2_500)

    const colours = await mapColourCount(page)
    if (colours < 24) {
      throw new Error(`the map drew almost nothing: ${colours} distinct colours in the screenshot of the panel`)
    }
    console.log(`the map is drawing: ${colours} distinct colours in the screenshot of the panel`)

    const frames = []
    for (let index = 0; index < FRAME_COUNT; index += 1) {
      // The recording walks through the controls of the map: the budget is
      // raised once, the camera is taken closer to the fleet, and then the
      // whole fleet is fitted again.
      if (index === 4) {
        await page.click('.segmented__option:has-text("100")')
      }
      if (index === 16) {
        await page.locator('.fleet-map').focus()
        for (let step = 0; step < 3; step += 1) {
          await page.keyboard.press('+')
        }
      }
      if (index === 30) {
        await page.keyboard.press('f')
      }
      frames.push(resize(decodePng(await page.screenshot()), GIF_WIDTH))
      await page.waitForTimeout(FRAME_INTERVAL_MS)
    }

    const { bytes: recording, planes } = encodeGif({
      frames,
      delayMs: FRAME_INTERVAL_MS,
      maxColours: MAX_COLOURS,
      delta: true,
    })
    await mkdir(dirname(OUTPUT), { recursive: true })
    await writeFile(OUTPUT, recording)

    const check = await checkRecording(browser, frames, planes)
    if (check.width !== GIF_WIDTH || check.frames !== frames.length) {
      throw new Error(
        `the recording came back as ${check.frames} frames of ${check.width}px, ` +
          `and ${frames.length} frames of ${GIF_WIDTH}px were captured`,
      )
    }
    if (check.wrongFrames !== check.expectedFrames) {
      throw new Error(
        `${check.expectedFrames - check.wrongFrames} of ${check.expectedFrames} frames of the ` +
          'recording do not decode back into the pixels that were written',
      )
    }
    // A palette of 128 colours cannot hold a gradient exactly, so the frames it
    // reproduces sit a few levels away from the screenshots; a stream a decoder
    // reads wrong moves that number by tens of levels instead.
    if (check.worst > 12) {
      throw new Error(
        `the recording reads back with a mean colour ${check.worst.toFixed(2)} levels off ` +
          `at frame ${check.worstFrame}, which is more than the palette can explain`,
      )
    }
    if (check.colours < 24) {
      throw new Error(`the frame the browser drew is nearly flat: ${check.colours} colours`)
    }
    if (check.closest > 3) {
      throw new Error(
        `the browser's own reading of the recording is ${check.closest.toFixed(2)} levels away ` +
          'from every frame that was captured, so it is not playing the recording',
      )
    }
    const bytes = await readFile(OUTPUT)
    console.log(
      `${frames.length} frames of ${frames[0].width}x${frames[0].height} wrote ${OUTPUT} ` +
        `(${(bytes.length / 1024).toFixed(0)} KiB; all ${check.expectedFrames} frames read back ` +
        `pixel for pixel, within ${check.worst.toFixed(2)} levels of the screenshots they were ` +
        `made of, and the browser drew ${check.colours} colours of one of them)`,
    )
  } finally {
    await browser.close()
    await new Promise((done) => demo.server.close(done))
  }
}

await main()
