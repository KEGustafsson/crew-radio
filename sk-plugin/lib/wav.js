// SPDX-License-Identifier: EUPL-1.2
"use strict";

/** The little bit of RIFF/WAVE the plugin needs: reading what Flite writes. */

const CHANNEL_RATE = 16_000;

/** Minimal RIFF/WAVE reader for PCM16 mono; `{rate, pcm}` with the samples copied out. */
function parseWav(b) {
  if (b.length < 44 || b.toString("ascii", 0, 4) !== "RIFF" || b.toString("ascii", 8, 12) !== "WAVE") throw new Error("Flite wrote no WAV");
  let off = 12, rate = 0, channels = 1, bits = 16, pcm = null;
  while (off + 8 <= b.length) {
    const id = b.toString("ascii", off, off + 4), size = b.readUInt32LE(off + 4);
    if (id === "fmt ") { channels = b.readUInt16LE(off + 10); rate = b.readUInt32LE(off + 12); bits = b.readUInt16LE(off + 22); }
    if (id === "data") { pcm = b.subarray(off + 8, Math.min(b.length, off + 8 + size)); break; }
    off += 8 + size + (size & 1);
  }
  if (!pcm || bits !== 16 || channels !== 1) throw new Error(`unexpected WAV from Flite (${bits} bit, ${channels} ch)`);
  return { rate, pcm: Buffer.from(pcm) };
}

module.exports = { parseWav, CHANNEL_RATE };
