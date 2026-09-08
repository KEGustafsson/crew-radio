// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * A token bucket per source for say(): each door (the PUT path, the REST route, the in-process
 * api, the notification bridge) has its own budget, so a runaway automation on one cannot
 * starve an alarm on another. Tokens refill continuously at `perMinute` and never pile up past
 * `burst`. Pure, with an injectable clock, like the app's RateLimiter.
 */

class SourceLimiter {
  /**
   * @param {object} [opts]
   * @param {number} [opts.perMinute=10]    the sustained rate for a source without its own entry
   * @param {number} [opts.burst]           the bucket size (default: the per-minute rate)
   * @param {Record<string, number>} [opts.rates]  per-source rates, e.g. {bridge: 30}
   * @param {() => number} [opts.now]       milliseconds
   */
  constructor(opts = {}) {
    this.perMinute = opts.perMinute ?? 10;
    this.burst = opts.burst ?? this.perMinute;
    this.rates = opts.rates ?? {};
    this.now = opts.now ?? Date.now;
    this.buckets = new Map();   // source -> {tokens, at}
  }

  /** The sustained rate for `source`, per minute. */
  rateOf(source) {
    return Object.hasOwn(this.rates, source) ? this.rates[source] : this.perMinute;
  }

  /** Takes one token for `source`; false when the source is over its budget. */
  allow(source) {
    const rate = this.rateOf(source);
    const burst = Math.max(1, Object.hasOwn(this.rates, source) ? rate : this.burst);
    const now = this.now();
    let b = this.buckets.get(source);
    if (!b) { b = { tokens: burst, at: now }; this.buckets.set(source, b); }
    b.tokens = Math.min(burst, b.tokens + ((now - b.at) / 60_000) * rate);
    b.at = now;
    if (b.tokens < 1) return false;
    b.tokens -= 1;
    return true;
  }
}

module.exports = { SourceLimiter };
