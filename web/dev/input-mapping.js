"use strict";

// Pointer Lock reports movement in CSS pixels, while GLFW's cursor position
// contract uses the canvas backing-store coordinates. Keep the conversion in
// one small, pure browser module so unlocked points and locked deltas share the
// exact same CSS-to-backing scale at DPR 1, DPR 2, and non-square canvases.
(() => {
  const finite = (value, fallback) => {
    const number = Number(value);
    return Number.isFinite(number) ? number : fallback;
  };

  function scale(rect, backingWidth, backingHeight) {
    const cssWidth = finite(rect?.width, 0);
    const cssHeight = finite(rect?.height, 0);
    const width = finite(backingWidth, 0);
    const height = finite(backingHeight, 0);
    return [
      cssWidth > 0 ? width / cssWidth : 1,
      cssHeight > 0 ? height / cssHeight : 1,
    ];
  }

  function point(clientX, clientY, rect, backingWidth, backingHeight) {
    const [scaleX, scaleY] = scale(rect, backingWidth, backingHeight);
    return [
      (finite(clientX, 0) - finite(rect?.left, 0)) * scaleX,
      (finite(clientY, 0) - finite(rect?.top, 0)) * scaleY,
    ];
  }

  function lockedDelta(movementX, movementY, rect, backingWidth, backingHeight) {
    const [scaleX, scaleY] = scale(rect, backingWidth, backingHeight);
    return [
      finite(movementX, 0) * scaleX,
      finite(movementY, 0) * scaleY,
    ];
  }

  globalThis.mcWebInputMapping = Object.freeze({ scale, point, lockedDelta });
})();
