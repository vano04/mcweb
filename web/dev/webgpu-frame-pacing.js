// Bounds queued canvas frames and exposes completion-based frame statistics.
"use strict";

(() => {
  if (globalThis.mcWebFramePacing) return;

  const create = ({maxFramesInFlight = 2, now = () => performance.now()} = {}) => {
    const limit = Math.max(1, Math.floor(maxFramesInFlight));
    const pending = [];
    const completionTimes = [];
    let submissions = 0;
    let completions = 0;
    let failures = 0;
    let lastLatencyMs = 0;

    const finish = (entry, failed) => {
      const index = pending.indexOf(entry);
      if (index >= 0) pending.splice(index, 1);
      const completedAt = now();
      completions++;
      if (failed) failures++;
      lastLatencyMs = Math.max(0, completedAt - entry.submittedAt);
      completionTimes.push(completedAt);
      if (completionTimes.length > 120) completionTimes.shift();
    };

    const submitted = (queue) => {
      if (!queue || typeof queue.onSubmittedWorkDone !== "function") return false;
      const entry = {submittedAt: now(), settled: null};
      let completion;
      try {
        completion = queue.onSubmittedWorkDone();
      } catch {
        failures++;
        return false;
      }
      submissions++;
      pending.push(entry);
      entry.settled = Promise.resolve(completion).then(
        () => finish(entry, false),
        () => finish(entry, true),
      );
      return true;
    };

    const waitForRoom = () => pending.length >= limit
      ? pending[0].settled
      : null;

    const report = () => {
      const first = completionTimes[0];
      const last = completionTimes[completionTimes.length - 1];
      const span = completionTimes.length > 1 ? last - first : 0;
      return {
        maxFramesInFlight: limit,
        pending: pending.length,
        submissions,
        completions,
        failures,
        completionFps: span > 0
          ? Math.round((completionTimes.length - 1) * 100000 / span) / 100
          : 0,
        lastLatencyMs: Math.round(lastLatencyMs * 100) / 100,
      };
    };

    return {submitted, waitForRoom, report};
  };

  globalThis.mcWebFramePacing = create();
  globalThis.mcWebFramePacing.create = create;
})();
