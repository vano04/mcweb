import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import test from "node:test";
import {runInNewContext} from "node:vm";

const ROOT = new URL("..", import.meta.url).pathname;

const deferred = () => {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return {promise, resolve, reject};
};

test("GPU frame pacing blocks the third frame until one of two queued frames completes", async () => {
  const source = await readFile(`${ROOT}/web/dev/webgpu-frame-pacing.js`, "utf8");
  let clock = 0;
  const context = {Promise, performance: {now: () => clock}};
  runInNewContext(source, context, {filename: "webgpu-frame-pacing.js"});
  const pacing = context.mcWebFramePacing.create({
    maxFramesInFlight: 2,
    now: () => clock,
  });
  const first = deferred();
  const second = deferred();
  const completions = [first.promise, second.promise];
  const queue = {onSubmittedWorkDone: () => completions.shift()};

  assert.equal(pacing.submitted(queue), true);
  clock = 1;
  assert.equal(pacing.submitted(queue), true);
  const room = pacing.waitForRoom();
  assert.ok(room instanceof Promise);
  assert.equal(pacing.report().pending, 2);

  clock = 20;
  first.resolve();
  await room;
  assert.equal(pacing.waitForRoom(), null);
  assert.equal(pacing.report().pending, 1);
  assert.equal(pacing.report().completions, 1);

  clock = 40;
  second.resolve();
  await second.promise;
  await Promise.resolve();
  assert.equal(pacing.report().pending, 0);
  assert.equal(pacing.report().completionFps, 50);
});

test("a rejected GPU completion fence releases frame-pump backpressure", async () => {
  const source = await readFile(`${ROOT}/web/dev/webgpu-frame-pacing.js`, "utf8");
  const context = {Promise, performance: {now: () => 0}};
  runInNewContext(source, context, {filename: "webgpu-frame-pacing.js"});
  const pacing = context.mcWebFramePacing.create({maxFramesInFlight: 1, now: () => 0});
  const completion = deferred();
  pacing.submitted({onSubmittedWorkDone: () => completion.promise});
  const room = pacing.waitForRoom();
  completion.reject(new Error("device lost"));
  await room;
  assert.equal(pacing.report().pending, 0);
  assert.equal(pacing.report().failures, 1);
});
