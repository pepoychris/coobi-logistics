/**
 * Just enough PNG to look at a screenshot.
 *
 * The recorder needs to know that the map it captured actually drew something,
 * and a WebGL canvas cannot be read back after the frame that drew it. So it
 * reads the screenshot instead - which is the thing the GIF is made of - and
 * that means decoding a PNG with nothing but the standard library: the chunks,
 * one `inflateSync`, and the five filters of the format.
 */

import { inflateSync } from 'node:zlib'

const SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])

/** Channels per pixel of the colour types this decoder understands. */
const CHANNELS = { 0: 1, 2: 3, 6: 4 }

/**
 * @param buffer the bytes of a PNG
 * @returns the pixels as RGBA rows, left to right and top to bottom
 */
export function decodePng(buffer) {
  if (!buffer.subarray(0, 8).equals(SIGNATURE)) {
    throw new Error('that file is not a PNG')
  }

  let offset = 8
  let header = null
  const data = []
  while (offset + 8 <= buffer.length) {
    const length = buffer.readUInt32BE(offset)
    const type = buffer.toString('ascii', offset + 4, offset + 8)
    const body = buffer.subarray(offset + 8, offset + 8 + length)
    if (type === 'IHDR') {
      header = {
        width: body.readUInt32BE(0),
        height: body.readUInt32BE(4),
        bitDepth: body[8],
        colorType: body[9],
        interlace: body[12],
      }
    } else if (type === 'IDAT') {
      data.push(body)
    } else if (type === 'IEND') {
      break
    }
    offset += 12 + length
  }

  if (!header) {
    throw new Error('the PNG has no header')
  }
  if (header.bitDepth !== 8 || header.interlace !== 0 || !(header.colorType in CHANNELS)) {
    throw new Error(`unsupported PNG: bit depth ${header.bitDepth}, colour type ${header.colorType}`)
  }

  const channels = CHANNELS[header.colorType]
  const stride = header.width * channels
  const raw = inflateSync(Buffer.concat(data))
  const pixels = Buffer.alloc(header.width * header.height * 4)
  let previous = Buffer.alloc(stride)
  let cursor = 0

  for (let row = 0; row < header.height; row += 1) {
    const filter = raw[cursor]
    cursor += 1
    const line = Buffer.from(raw.subarray(cursor, cursor + stride))
    cursor += stride
    for (let index = 0; index < stride; index += 1) {
      const left = index >= channels ? line[index - channels] : 0
      const up = previous[index]
      const upLeft = index >= channels ? previous[index - channels] : 0
      switch (filter) {
        case 1:
          line[index] = (line[index] + left) & 0xff
          break
        case 2:
          line[index] = (line[index] + up) & 0xff
          break
        case 3:
          line[index] = (line[index] + ((left + up) >> 1)) & 0xff
          break
        case 4: {
          const estimate = left + up - upLeft
          const distances = [Math.abs(estimate - left), Math.abs(estimate - up), Math.abs(estimate - upLeft)]
          const best = distances.indexOf(Math.min(...distances))
          line[index] = (line[index] + (best === 0 ? left : best === 1 ? up : upLeft)) & 0xff
          break
        }
        default:
          break
      }
    }
    for (let column = 0; column < header.width; column += 1) {
      const source = column * channels
      const target = (row * header.width + column) * 4
      pixels[target] = line[source]
      pixels[target + 1] = channels === 1 ? line[source] : line[source + 1]
      pixels[target + 2] = channels === 1 ? line[source] : line[source + 2]
      pixels[target + 3] = channels === 4 ? line[source + 3] : 0xff
    }
    previous = line
  }

  return { width: header.width, height: header.height, pixels }
}

/**
 * Counts the colours of a decoded image, sampling it on a grid.
 *
 * @param image a decoded PNG
 * @param stride how many pixels to skip between samples
 * @returns how many distinct colours the sample found
 */
export function countColours(image, stride = 7) {
  const seen = new Set()
  for (let y = 0; y < image.height; y += stride) {
    for (let x = 0; x < image.width; x += stride) {
      const index = (y * image.width + x) * 4
      seen.add((image.pixels[index] << 16) | (image.pixels[index + 1] << 8) | image.pixels[index + 2])
    }
  }
  return seen.size
}
