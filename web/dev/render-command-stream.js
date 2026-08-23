(() => {
  "use strict";

  // MCRP v1. Keep these values in sync with WebGpuRenderPass.java.
  const MAGIC = 0x4d435250;
  const VERSION = 1;
  const OP = Object.freeze({
    SET_PIPELINE: 1,
    BIND_TEXTURE: 2,
    SET_UNIFORM: 3,
    SET_VERTEX_BUFFER: 4,
    SET_INDEX_BUFFER: 5,
    SCISSOR: 6,
    DISABLE_SCISSOR: 7,
    DRAW: 8,
    DRAW_INDEXED: 9,
    DRAW_INDIRECT: 10,
    DRAW_INDEXED_INDIRECT: 11,
    PUSH_DEBUG_GROUP: 12,
    POP_DEBUG_GROUP: 13
  });

  function replayWords(wordCount, uncheckedRead, handlers, host, passHandle) {
    if (wordCount === 0) return 0;
    if (wordCount < 2) throw new Error("render command stream header is truncated");
    let offset = 0;
    const read = () => {
      if (offset >= wordCount) throw new Error("render command stream is truncated");
      offset++;
      return uncheckedRead();
    };

    const magic = read();
    const version = read();
    if (magic !== MAGIC) {
      throw new Error(`render command stream magic mismatch: 0x${(magic >>> 0).toString(16)}`);
    }
    if (version !== VERSION) {
      throw new Error(`unsupported render command stream version ${version}`);
    }

    let commandCount = 0;
    while (offset < wordCount) {
      const opcode = read();
      commandCount++;
      switch (opcode) {
        case OP.SET_PIPELINE:
          handlers.setPipeline(host, passHandle, read());
          break;
        case OP.BIND_TEXTURE:
          handlers.bindTexture(host, passHandle, read(), read(), read());
          break;
        case OP.SET_UNIFORM:
          handlers.setUniform(host, passHandle, read(), read(), read(), read());
          break;
        case OP.SET_VERTEX_BUFFER:
          handlers.setVertexBuffer(host, passHandle, read(), read(), read(), read());
          break;
        case OP.SET_INDEX_BUFFER:
          handlers.setIndexBuffer(host, passHandle, read(), read());
          break;
        case OP.SCISSOR:
          handlers.scissor(host, passHandle, read(), read(), read(), read());
          break;
        case OP.DISABLE_SCISSOR:
          handlers.disableScissor(host, passHandle);
          break;
        case OP.DRAW:
          handlers.draw(host, passHandle, read(), read(), read(), read());
          break;
        case OP.DRAW_INDEXED:
          handlers.drawIndexed(host, passHandle, read(), read(), read(), read(), read());
          break;
        case OP.DRAW_INDIRECT:
          handlers.drawIndirect(host, passHandle, read(), read(), read());
          break;
        case OP.DRAW_INDEXED_INDIRECT:
          handlers.drawIndexedIndirect(host, passHandle, read(), read(), read());
          break;
        case OP.PUSH_DEBUG_GROUP:
          handlers.pushDebugGroup(host, passHandle, read());
          break;
        case OP.POP_DEBUG_GROUP:
          handlers.popDebugGroup(host, passHandle);
          break;
        default:
          throw new Error(`unknown render command opcode ${opcode}`);
      }
    }
    return commandCount;
  }

  /**
   * Decode and synchronously replay one little-endian MCRP command buffer.
   *
   * `bytes` may be a WasmLM linear-memory view, so neither this function nor a
   * handler may retain it after replay returns. The handlers are passed the
   * host and pass handle separately to keep the hot loop allocation-free.
   */
  function replay(bytes, byteLength, handlers, host, passHandle) {
    if (!bytes || bytes.BYTES_PER_ELEMENT !== 1) {
      throw new TypeError("render command stream must be a byte view");
    }
    if (!Number.isInteger(byteLength) || byteLength < 0 || byteLength > bytes.byteLength) {
      throw new RangeError(`invalid render command byte length ${byteLength}`);
    }
    if ((byteLength & 3) !== 0) {
      throw new Error(`render command stream is not word aligned: ${byteLength}`);
    }
    const view = new DataView(bytes.buffer, bytes.byteOffset, byteLength);
    let byteOffset = 0;
    return replayWords(byteLength >>> 2, () => {
      const value = view.getInt32(byteOffset, true);
      byteOffset += 4;
      return value;
    }, handlers, host, passHandle);
  }

  /** Decode the same i32 words packed into two UTF-16 code units each. */
  function replayText(value, wordCount, handlers, host, passHandle) {
    if (typeof value !== "string") {
      throw new TypeError("packed render command stream must be a string");
    }
    if (!Number.isInteger(wordCount) || wordCount < 0 || value.length !== wordCount * 2) {
      throw new RangeError(
        `invalid packed render command length chars=${value.length} words=${wordCount}`
      );
    }
    let charOffset = 0;
    return replayWords(wordCount, () => {
      const low = value.charCodeAt(charOffset++);
      const high = value.charCodeAt(charOffset++);
      return (low | (high << 16)) | 0;
    }, handlers, host, passHandle);
  }

  /**
   * Replay words held in an opaque WasmGC Java int[]. `readWord` is the exact
   * Web Image export for that array type; keeping the reference and reader raw
   * avoids materializing the command stream as a JavaScript String.
   */
  function replayReader(words, wordCount, readWord, handlers, host, passHandle) {
    if (!Number.isInteger(wordCount) || wordCount < 0) {
      throw new RangeError(`invalid raw render command word count ${wordCount}`);
    }
    if (typeof readWord !== "function") {
      throw new TypeError("raw render command reader must be a function");
    }
    let wordOffset = 0;
    return replayWords(wordCount, () => readWord(words, wordOffset++) | 0,
      handlers, host, passHandle);
  }

  globalThis.mcWebRenderCommands = Object.freeze({
    MAGIC, VERSION, OP, replay, replayText, replayReader
  });
})();
