/**
 * A GIF encoder with no dependencies, so the recorder needs nothing but Node.
 *
 * It exists because the ffmpeg that ships with Playwright is a stripped build
 * - it records WebM and knows no PNG decoder - and because the recording has to
 * be reproducible on a machine that has nothing installed but the browser the
 * dashboard is checked in.
 *
 * Three things make the file small enough to live in a repository:
 *
 * - one palette for the whole recording, built by median cut over every frame,
 *   so colours never flicker between frames;
 * - a cache of the colours the recording actually uses, which turns a pixel
 *   into a palette index in one map read;
 * - transparent pixels for what did not change, so a frame only carries the
 *   part of the screen that moved and the rest is left as the previous frame
 *   left it.
 */

/** Codes are at most 12 bits wide, which is what the GIF format allows. */
const MAX_CODE_BITS = 12

/** The first code a GIF dictionary cannot hold; the table is emptied there. */
const MAX_CODES = 4096

/** The most colours a GIF palette can hold. */
export const MAX_COLOURS = 256

/**
 * Averages an image down to a width, keeping its shape.
 *
 * @param image decoded image, RGBA
 * @param width width of the result
 * @returns the smaller image
 */
export function resize(image, width) {
  if (width >= image.width) {
    return image
  }
  const scale = image.width / width
  const height = Math.max(1, Math.round(image.height / scale))
  const pixels = Buffer.alloc(width * height * 4)
  for (let y = 0; y < height; y += 1) {
    const fromY = Math.floor(y * scale)
    const toY = Math.min(image.height, Math.max(fromY + 1, Math.floor((y + 1) * scale)))
    for (let x = 0; x < width; x += 1) {
      const fromX = Math.floor(x * scale)
      const toX = Math.min(image.width, Math.max(fromX + 1, Math.floor((x + 1) * scale)))
      let red = 0
      let green = 0
      let blue = 0
      let count = 0
      for (let sourceY = fromY; sourceY < toY; sourceY += 1) {
        for (let sourceX = fromX; sourceX < toX; sourceX += 1) {
          const source = (sourceY * image.width + sourceX) * 4
          red += image.pixels[source]
          green += image.pixels[source + 1]
          blue += image.pixels[source + 2]
          count += 1
        }
      }
      const target = (y * width + x) * 4
      pixels[target] = Math.round(red / count)
      pixels[target + 1] = Math.round(green / count)
      pixels[target + 2] = Math.round(blue / count)
      pixels[target + 3] = 0xff
    }
  }
  return { width, height, pixels }
}

/** One colour box of the median cut. */
function boxOf(colours, from, to) {
  let minRed = 255
  let minGreen = 255
  let minBlue = 255
  let maxRed = 0
  let maxGreen = 0
  let maxBlue = 0
  for (let index = from; index < to; index += 1) {
    const colour = colours[index]
    minRed = Math.min(minRed, colour[0])
    maxRed = Math.max(maxRed, colour[0])
    minGreen = Math.min(minGreen, colour[1])
    maxGreen = Math.max(maxGreen, colour[1])
    minBlue = Math.min(minBlue, colour[2])
    maxBlue = Math.max(maxBlue, colour[2])
  }
  return {
    from,
    to,
    range: Math.max(maxRed - minRed, maxGreen - minGreen, maxBlue - minBlue),
    size: to - from,
    span: (maxRed - minRed) * (maxRed - minRed) + (maxGreen - minGreen) * (maxGreen - minGreen) + (maxBlue - minBlue) * (maxBlue - minBlue),
  }
}

/**
 * Builds one palette for a recording, by cutting the colour space in half
 * wherever it is widest until there are enough boxes.
 *
 * @param frames images of the recording, RGBA
 * @param maxColours how many colours the palette may hold
 * @returns the colours, three bytes each, brightest first
 */
export function buildPalette(frames, maxColours) {
  const histogram = new Map()
  // Sampling every fourth pixel of every frame is plenty for a palette while
  // keeping the pass over a recording of tens of frames quick.
  for (const frame of frames) {
    for (let index = 0; index < frame.pixels.length; index += 16) {
      const key = (frame.pixels[index] << 16) | (frame.pixels[index + 1] << 8) | frame.pixels[index + 2]
      histogram.set(key, (histogram.get(key) ?? 0) + 1)
    }
  }

  const colours = [...histogram.keys()].map((key) => [(key >> 16) & 0xff, (key >> 8) & 0xff, key & 0xff])
  if (colours.length <= maxColours) {
    return colours.map(([red, green, blue]) => [red, green, blue])
  }

  let boxes = [boxOf(colours, 0, colours.length)]
  while (boxes.length < maxColours) {
    let target = -1
    let widest = 0
    for (let index = 0; index < boxes.length; index += 1) {
      const box = boxes[index]
      if (box.size > 1 && box.range > widest) {
        widest = box.range
        target = index
      }
    }
    if (target === -1) {
      break
    }
    const box = boxes[target]
    const slice = colours.slice(box.from, box.to)
    const channel = widestChannel(slice)
    slice.sort((left, right) => left[channel] - right[channel])
    for (let index = 0; index < slice.length; index += 1) {
      colours[box.from + index] = slice[index]
    }
    const middle = box.from + Math.floor((box.to - box.from) / 2)
    boxes = [
      ...boxes.slice(0, target),
      boxOf(colours, box.from, middle),
      boxOf(colours, middle, box.to),
      ...boxes.slice(target + 1),
    ]
  }

  return boxes.map((box) => {
    let red = 0
    let green = 0
    let blue = 0
    for (let index = box.from; index < box.to; index += 1) {
      red += colours[index][0]
      green += colours[index][1]
      blue += colours[index][2]
    }
    const count = Math.max(1, box.to - box.from)
    return [Math.round(red / count), Math.round(green / count), Math.round(blue / count)]
  })
}

/** The channel a slice of colours is widest in, which is the one to cut on. */
function widestChannel(colours) {
  const spans = [0, 0, 0]
  const minima = [255, 255, 255]
  const maxima = [0, 0, 0]
  for (const colour of colours) {
    for (let channel = 0; channel < 3; channel += 1) {
      minima[channel] = Math.min(minima[channel], colour[channel])
      maxima[channel] = Math.max(maxima[channel], colour[channel])
    }
  }
  for (let channel = 0; channel < 3; channel += 1) {
    spans[channel] = maxima[channel] - minima[channel]
  }
  return spans.indexOf(Math.max(...spans))
}

/**
 * Builds the function that turns a colour into a palette index.
 *
 * Every distinct colour of the recording is searched once and remembered, so
 * the search costs what the recording uses rather than what the colour space
 * holds. A table keyed on the leading bits of each channel would be cheaper
 * still, and it was the first thing this encoder did - but dropping the low
 * bits of a channel biases every colour it looks up towards the dark end of
 * the palette, which showed up as a recording whose average colour sat five
 * levels below the screenshots it was made of.
 *
 * @param palette colours of the recording, three bytes each
 * @returns a function from a colour to its nearest entry of the palette
 */
function buildLookup(palette) {
  const known = new Map()
  return (red, green, blue) => {
    const key = (red << 16) | (green << 8) | blue
    const remembered = known.get(key)
    if (remembered !== undefined) {
      return remembered
    }
    let best = 0
    let bestDistance = Number.POSITIVE_INFINITY
    for (let index = 0; index < palette.length; index += 1) {
      const colour = palette[index]
      const distance =
        (red - colour[0]) * (red - colour[0]) +
        (green - colour[1]) * (green - colour[1]) +
        (blue - colour[2]) * (blue - colour[2])
      if (distance < bestDistance) {
        bestDistance = distance
        best = index
      }
    }
    known.set(key, best)
    return best
  }
}

/**
 * Writes bits least significant first, which is how a GIF code stream is read.
 */
class BitWriter {
  constructor() {
    this.bytes = []
    this.current = 0
    this.bits = 0
  }

  write(value, width) {
    this.current |= value << this.bits
    this.bits += width
    while (this.bits >= 8) {
      this.bytes.push(this.current & 0xff)
      this.current >>= 8
      this.bits -= 8
    }
  }

  flush() {
    if (this.bits > 0) {
      this.bytes.push(this.current & 0xff)
      this.current = 0
      this.bits = 0
    }
    return Buffer.from(this.bytes)
  }
}

/**
 * Compresses indices with the LZW variant the GIF format uses.
 *
 * @param indices one palette index per pixel
 * @param minimumCodeSize bits of the palette, at least two
 * @returns the compressed stream, sub-blocked as the format requires
 */
function compressLzw(indices, minimumCodeSize) {
  const clearCode = 1 << minimumCodeSize
  const endCode = clearCode + 1
  const writer = new BitWriter()
  const dictionary = new Int32Array(1 << 20).fill(-1)

  let codeSize = minimumCodeSize + 1
  let nextCode = clearCode + 2
  writer.write(clearCode, codeSize)

  if (indices.length === 0) {
    writer.write(endCode, codeSize)
  } else {
    let prefix = indices[0]
    for (let index = 1; index < indices.length; index += 1) {
      const pixel = indices[index]
      const key = (prefix << 8) | pixel
      const known = dictionary[key]
      if (known !== -1) {
        prefix = known
        continue
      }
      writer.write(prefix, codeSize)
      if (nextCode < MAX_CODES) {
        dictionary[key] = nextCode
        nextCode += 1
        // A decoder builds the dictionary one entry behind this encoder: it
        // reads a code, then learns the pair it stands for. It therefore widens
        // the code as soon as its own next code reaches the width, which is one
        // entry after this table's next code does. Widening on the decoder's
        // boundary instead - or widening any earlier - desynchronises the two
        // for the rest of the frame, which is not a theory: it was measured
        // against a browser's decoder, which read every pixel of a test image
        // wrong until this line moved one code later.
        if (codeSize < MAX_CODE_BITS && nextCode > 1 << codeSize) {
          codeSize += 1
        }
      } else {
        writer.write(clearCode, codeSize)
        dictionary.fill(-1)
        codeSize = minimumCodeSize + 1
        nextCode = clearCode + 2
      }
      prefix = pixel
    }
    writer.write(prefix, codeSize)
    writer.write(endCode, codeSize)
  }

  const bytes = writer.flush()
  const blocks = [Buffer.from([minimumCodeSize])]
  for (let offset = 0; offset < bytes.length; offset += 255) {
    const block = bytes.subarray(offset, offset + 255)
    blocks.push(Buffer.from([block.length]), block)
  }
  blocks.push(Buffer.from([0]))
  return Buffer.concat(blocks)
}

/**
 * Encodes a recording as an animated GIF.
 *
 * @param options frames (RGBA, all the same size), delay in milliseconds, how
 *        many colours the palette may hold, and whether a frame may carry only
 *        what changed since the one before it
 * @returns the bytes of the GIF, the palette it was written with, and the exact
 *          indices every frame carries - which is what the recorder proves a
 *          decoder reads back
 */
export function encodeGif(options) {
  const { frames, delayMs } = options
  if (frames.length === 0) {
    throw new Error('a recording needs at least one frame')
  }
  const { width, height } = frames[0]
  const delta = options.delta ?? true
  const colours = buildPalette(frames, Math.min(options.maxColours ?? 128, MAX_COLOURS - (delta ? 1 : 0)))
  // Index 0 is the transparent pixel of a delta frame, and a colour of its own
  // otherwise; the palette follows it either way.
  const palette = delta ? [[0, 0, 0], ...colours] : colours
  // The lookup is built over the colours themselves, so a pixel is never
  // quantized onto the entry that delta frames reserve for "unchanged".
  const lookup = buildLookup(colours)
  const minimumCodeSize = Math.max(2, Math.ceil(Math.log2(Math.max(2, palette.length))))
  const tableSize = 1 << minimumCodeSize

  const parts = []
  parts.push(Buffer.from('GIF89a', 'ascii'))

  const screen = Buffer.alloc(7)
  screen.writeUInt16LE(width, 0)
  screen.writeUInt16LE(height, 2)
  screen[4] = 0xf0 | (minimumCodeSize - 1)
  screen[5] = 0
  screen[6] = 0
  parts.push(screen)

  const table = Buffer.alloc(tableSize * 3)
  for (let index = 0; index < tableSize; index += 1) {
    const colour = palette[Math.min(index, palette.length - 1)]
    table[index * 3] = colour[0]
    table[index * 3 + 1] = colour[1]
    table[index * 3 + 2] = colour[2]
  }
  parts.push(table)

  // The loop the format needs to animate for ever.
  parts.push(Buffer.from([0x21, 0xff, 0x0b]), Buffer.from('NETSCAPE2.0', 'ascii'), Buffer.from([0x03, 0x01, 0x00, 0x00, 0x00]))

  const delay = Math.max(2, Math.round(delayMs / 10))
  const indices = new Uint8Array(width * height)
  const planes = []
  let previous = null

  for (const frame of frames) {
    const graphic = Buffer.alloc(8)
    graphic[0] = 0x21
    graphic[1] = 0xf9
    graphic[2] = 0x04
    // Disposal 1 - leave the frame in place - because the next one carries
    // only what changed, and no transparency at all when nothing did.
    // Bits: reserved, disposal 1 (leave the frame in place), no user input,
    // transparent when the frame carries only what changed.
    graphic[3] = delta ? 0x05 : 0x04
    graphic.writeUInt16LE(delay, 4)
    graphic[6] = delta ? 0 : 0
    graphic[7] = 0
    parts.push(graphic)

    const descriptor = Buffer.alloc(10)
    descriptor[0] = 0x2c
    descriptor.writeUInt16LE(0, 1)
    descriptor.writeUInt16LE(0, 3)
    descriptor.writeUInt16LE(width, 5)
    descriptor.writeUInt16LE(height, 7)
    descriptor[9] = 0
    parts.push(descriptor)

    for (let pixel = 0; pixel < width * height; pixel += 1) {
      const offset = pixel * 4
      const colour = lookup(frame.pixels[offset], frame.pixels[offset + 1], frame.pixels[offset + 2])
      if (delta && previous) {
        const same =
          previous[offset] === frame.pixels[offset] &&
          previous[offset + 1] === frame.pixels[offset + 1] &&
          previous[offset + 2] === frame.pixels[offset + 2]
        indices[pixel] = same ? 0 : colour + 1
      } else {
        indices[pixel] = delta ? colour + 1 : colour
      }
    }
    previous = frame.pixels
    parts.push(compressLzw(indices, minimumCodeSize))
    planes.push(Uint8Array.from(indices))
  }

  parts.push(Buffer.from([0x3b]))
  return { bytes: Buffer.concat(parts), palette, planes }
}

/**
 * Reads a recording back, the way a decoder does.
 *
 * The recorder writes the GIF and then reads it with this, so the check that it
 * produced the recording it captured is deterministic and needs no browser. It
 * is the other half of the format: where `encodeGif` widens a code, this widens
 * it at the boundary a decoder widens it at, which is the boundary a browser
 * confirmed by reading a test image of this encoder back pixel for pixel.
 *
 * @param bytes the bytes of a GIF
 * @returns its size, its palette and every frame as palette indices
 */
export function decodeFrames(bytes) {
  if (bytes.toString('ascii', 0, 6) !== 'GIF89a') {
    throw new Error('that file is not a GIF89a recording')
  }
  const width = bytes.readUInt16LE(6)
  const height = bytes.readUInt16LE(8)
  const screen = bytes[10]
  const globalSize = 1 << ((screen & 0x07) + 1)
  const global = bytes.subarray(13, 13 + globalSize * 3)
  let offset = 13 + globalSize * 3
  let transparent = null
  const frames = []

  while (offset < bytes.length) {
    const marker = bytes[offset]
    if (marker === 0x3b) {
      break
    }
    if (marker === 0x21) {
      const label = bytes[offset + 1]
      offset += 2
      if (label === 0xf9) {
        const size = bytes[offset]
        transparent = bytes[offset + 1] & 1 ? bytes[offset + 4] : null
        offset += size + 2
      } else {
        while (bytes[offset] !== 0) {
          offset += bytes[offset] + 1
        }
        offset += 1
      }
      continue
    }
    if (marker !== 0x2c) {
      throw new Error(`unexpected block 0x${marker.toString(16)} at ${offset}`)
    }
    const left = bytes.readUInt16LE(offset + 1)
    const top = bytes.readUInt16LE(offset + 3)
    const frameWidth = bytes.readUInt16LE(offset + 5)
    const frameHeight = bytes.readUInt16LE(offset + 7)
    const packed = bytes[offset + 9]
    offset += 10
    let palette = global
    if (packed & 0x80) {
      const localSize = 1 << ((packed & 0x07) + 1)
      palette = bytes.subarray(offset, offset + localSize * 3)
      offset += localSize * 3
    }
    const minimumCodeSize = bytes[offset]
    offset += 1
    const chunks = []
    while (bytes[offset] !== 0) {
      const size = bytes[offset]
      chunks.push(bytes.subarray(offset + 1, offset + 1 + size))
      offset += size + 1
    }
    offset += 1
    frames.push({
      left,
      top,
      width: frameWidth,
      height: frameHeight,
      transparent,
      palette,
      indices: inflate(Buffer.concat(chunks), minimumCodeSize, frameWidth * frameHeight),
    })
  }

  return { width, height, frames }
}

/**
 * The LZW stream of one frame, read back into palette indices.
 *
 * @param data the compressed sub-blocks of the frame
 * @param minimumCodeSize bits of the palette of the frame
 * @param count how many pixels the frame claims to have
 * @returns one palette index per pixel
 */
function inflate(data, minimumCodeSize, count) {
  const clearCode = 1 << minimumCodeSize
  const endCode = clearCode + 1
  const indices = new Uint8Array(count)
  let written = 0
  let table = []
  let codeSize = minimumCodeSize + 1
  let nextCode = clearCode + 2
  let bit = 0

  const reset = () => {
    table = []
    for (let index = 0; index < clearCode; index += 1) {
      table.push([index])
    }
    table.push([], [])
    codeSize = minimumCodeSize + 1
    nextCode = clearCode + 2
  }
  const read = () => {
    if (bit + codeSize > data.length * 8) {
      return undefined
    }
    let value = 0
    for (let position = 0; position < codeSize; position += 1) {
      value |= ((data[(bit + position) >> 3] >> ((bit + position) & 7)) & 1) << position
    }
    bit += codeSize
    return value
  }

  reset()
  let previous = null
  for (;;) {
    const code = read()
    if (code === undefined || code === endCode) {
      break
    }
    if (code === clearCode) {
      reset()
      previous = null
      continue
    }
    let entry
    if (code < table.length && table[code].length > 0) {
      entry = table[code]
    } else if (previous && code === nextCode) {
      entry = [...previous, previous[0]]
    } else {
      throw new Error(`code ${code} is not in the table of the frame`)
    }
    for (const index of entry) {
      if (written >= count) {
        throw new Error('the frame holds more pixels than it claims')
      }
      indices[written] = index
      written += 1
    }
    if (previous && nextCode < 4096) {
      table.push([...previous, entry[0]])
      nextCode += 1
      // The boundary a decoder widens at, which is the boundary the encoder
      // widens at one entry of its own later.
      if (nextCode === 1 << codeSize && codeSize < MAX_CODE_BITS) {
        codeSize += 1
      }
    }
    previous = entry
  }

  if (written !== count) {
    throw new Error(`the frame decoded ${written} pixels instead of ${count}`)
  }
  return indices
}

/**
 * Paints decoded frames onto one canvas, which is what a reader sees play.
 *
 * @param recording a decoded recording
 * @returns one RGBA image per frame, as the animation composited at that frame
 */
export function composite(recording) {
  const { width, height, frames } = recording
  const canvas = Buffer.alloc(width * height * 4)
  const images = []
  for (const frame of frames) {
    for (let y = 0; y < frame.height; y += 1) {
      for (let x = 0; x < frame.width; x += 1) {
        const index = frame.indices[y * frame.width + x]
        if (frame.transparent !== null && index === frame.transparent) {
          continue
        }
        const target = ((frame.top + y) * width + frame.left + x) * 4
        canvas[target] = frame.palette[index * 3]
        canvas[target + 1] = frame.palette[index * 3 + 1]
        canvas[target + 2] = frame.palette[index * 3 + 2]
        canvas[target + 3] = 0xff
      }
    }
    images.push({ width, height, pixels: Buffer.from(canvas) })
  }
  return images
}
