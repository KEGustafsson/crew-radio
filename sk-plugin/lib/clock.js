// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * Milliseconds on a monotonic clock that never starts near 0: the process's start in wall time
 * plus the monotonic time since. Elapsed times (silence, busy, talking, ages, stalls) use it, so a
 * server clock stepped back by GPS or NTP keeps no quiet channel busy, while 0 still means "never"
 * to the code that keeps a last-heard time: performance.now() alone counts from the process's
 * start, and in its first seconds a member never heard looked as if just heard (CI caught it,
 * 2026-11-08).
 */
const mono = () => performance.timeOrigin + performance.now();

module.exports = { mono };
