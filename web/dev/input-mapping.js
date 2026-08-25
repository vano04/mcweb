"use strict";

// Unlocked cursor points use canvas backing-store coordinates. Pointer Lock
// movement is already a relative input value; scaling it by the canvas backing
// ratio changes the camera sensitivity independently on each axis.
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
    return [
      finite(movementX, 0),
      finite(movementY, 0),
    ];
  }

  globalThis.mcWebInputMapping = Object.freeze({ scale, point, lockedDelta });
})();
