/**
 * Bidirectional packet transport over MessagePort.
 *
 * Frames are length-prefixed: [4-byte big-endian length][payload].
 * The transport coalesces small writes and delivers complete frames
 * to the registered handler.
 */
export class PacketTransport {
  #port;
  #handler;
  #controlHandler;
  #sendQueue = [];
  #sendQueueBytes = 0;
  #flushTimer = null;
  #closed = false;

  constructor(port, controlHandler = null) {
    this.#port = port;
    this.#controlHandler = controlHandler;
    this.#port.onmessage = (event) => this.#onMessage(event);
    this.#port.start();
  }
  onControl(handler) {
    this.#controlHandler = handler;
  }
  sendControl(message) {
    if (!this.#closed) this.#port.postMessage(message);
  }

  onPacket(handler) {
    this.#handler = handler;
  }

  send(bytes) {
    if (this.#closed) return;
    const frame = new Uint8Array(4 + bytes.length);
    new DataView(frame.buffer).setUint32(0, bytes.length, false);
    frame.set(bytes, 4);
    this.#sendQueue.push(frame);
    this.#sendQueueBytes += frame.byteLength;
    if (!this.#flushTimer) {
      this.#flushTimer = setTimeout(() => this.#flush(), 0);
    }
  }

  /**
   * Posts the batch accumulated so far without waiting for the host event loop.
   * The server Worker uses this after handling player input and after the
   * vanilla tick, before it enters another potentially long worldgen drain.
   */
  flush() {
    if (this.#flushTimer) {
      clearTimeout(this.#flushTimer);
      this.#flushTimer = null;
    }
    this.#flush();
  }

  #flush() {
    this.#flushTimer = null;
    if (this.#closed || this.#sendQueue.length === 0) return;
    const batch = this.#sendQueue;
    const byteLength = this.#sendQueueBytes;
    this.#sendQueue = [];
    this.#sendQueueBytes = 0;
    let outgoing;
    if (batch.length === 1) {
      outgoing = batch[0];
    } else {
      outgoing = new Uint8Array(byteLength);
      let offset = 0;
      for (const frame of batch) {
        outgoing.set(frame, offset);
        offset += frame.byteLength;
      }
    }
    this.#port.postMessage(outgoing, [outgoing.buffer]);
  }

  #recvBuf = null;
  #recvOff = 0;

  #onMessage(event) {
    if (this.#closed) return;
    const data = event.data;
    if (!(data instanceof ArrayBuffer || data instanceof Uint8Array)) {
      if (this.#controlHandler) this.#controlHandler(data);
      return;
    }
    const incoming = data instanceof Uint8Array ? data : new Uint8Array(data);

    if (this.#recvBuf && this.#recvOff < this.#recvBuf.length) {
      const merged = new Uint8Array(this.#recvBuf.length - this.#recvOff + incoming.length);
      merged.set(this.#recvBuf.subarray(this.#recvOff), 0);
      merged.set(incoming, this.#recvBuf.length - this.#recvOff);
      this.#recvBuf = merged;
      this.#recvOff = 0;
    } else {
      this.#recvBuf = incoming;
      this.#recvOff = 0;
    }

    while (this.#recvOff + 4 <= this.#recvBuf.length) {
      const len = new DataView(
        this.#recvBuf.buffer,
        this.#recvBuf.byteOffset + this.#recvOff,
        4
      ).getUint32(0, false);
      if (this.#recvOff + 4 + len > this.#recvBuf.length) break;
      const payload = this.#recvBuf.slice(this.#recvOff + 4, this.#recvOff + 4 + len);
      this.#recvOff += 4 + len;
      if (this.#handler) this.#handler(payload);
    }

    if (this.#recvOff >= this.#recvBuf.length) {
      this.#recvBuf = null;
      this.#recvOff = 0;
    }
  }

  close() {
    this.#closed = true;
    if (this.#flushTimer) {
      clearTimeout(this.#flushTimer);
      this.#flushTimer = null;
    }
    this.#sendQueue = [];
    this.#sendQueueBytes = 0;
    this.#port.close();
  }
}
