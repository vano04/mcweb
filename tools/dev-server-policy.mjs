/**
 * Pure request-boundary helpers for the integrated local server.
 *
 * Keeping these checks free of server startup makes the loopback upload
 * boundary directly testable without opening a second listener.
 */

export function isLoopbackAddress(address) {
  const value = String(address || "").replace(/^::ffff:/i, "");
  return value === "127.0.0.1" || value === "::1" || value === "0:0:0:0:0:0:0:1";
}

function requestUsesLoopbackHost(request) {
  try {
    const host = new URL(`http://${String(request.headers?.host || "")}`).hostname;
    return isLoopbackAddress(host);
  } catch {
    return false;
  }
}

export function trustedLauncherUploadPeer(request) {
  if (!requestUsesLoopbackHost(request)) return false;
  return isLoopbackAddress(request?.socket?.remoteAddress);
}

export function sameOriginRequest(request) {
  const origin = request?.headers?.origin;
  if (!origin) return false;
  try {
    const parsed = new URL(String(origin));
    return (parsed.protocol === "http:" || parsed.protocol === "https:")
      && parsed.host === request?.headers?.host;
  } catch {
    return false;
  }
}
