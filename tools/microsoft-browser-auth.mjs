export const MINECRAFT_LAUNCHER_LOGIN_URL = "https://api.minecraftservices.com/launcher/login";
export const MINECRAFT_ENTITLEMENTS_URL = "https://api.minecraftservices.com/entitlements/mcstore";
export const MINECRAFT_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";

const USER_HASH = /^[A-Za-z0-9_-]{1,128}$/;
const MAX_XSTS_TOKEN_BYTES = 64 * 1024;
const MAX_BODY_BYTES = 96 * 1024;

async function jsonRequest(fetchImpl, url, options, label) {
  const response = await fetchImpl(url, options);
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const detail = body.error_description || body.error?.message || body.error || "request rejected";
    throw Object.assign(new Error(`${label} failed (${response.status}): ${detail}`), {
      status: response.status,
    });
  }
  return body;
}

export async function exchangeXstsForMinecraft({ userHash, xstsToken }, {
  fetchImpl = fetch,
  now = () => Date.now(),
} = {}) {
  const hash = String(userHash || "").trim();
  const token = String(xstsToken || "").trim();
  if (!USER_HASH.test(hash) || !token
      || Buffer.byteLength(token, "utf8") > MAX_XSTS_TOKEN_BYTES) {
    throw new Error("Xbox returned an invalid Minecraft security proof");
  }
  const minecraft = await jsonRequest(fetchImpl, MINECRAFT_LAUNCHER_LOGIN_URL, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      xtoken: `XBL3.0 x=${hash};${token}`,
      platform: "PC_LAUNCHER",
    }),
  }, "Minecraft services login");
  const accessToken = String(minecraft.access_token || "");
  if (!accessToken) throw new Error("Minecraft services did not return an access token");
  const authHeaders = { authorization: `Bearer ${accessToken}` };
  const entitlements = await jsonRequest(fetchImpl, MINECRAFT_ENTITLEMENTS_URL, {
    headers: authHeaders,
  }, "Minecraft ownership check");
  if (!Array.isArray(entitlements.items) || entitlements.items.length === 0) {
    throw new Error("This Microsoft account does not own Minecraft: Java Edition");
  }
  const profile = await jsonRequest(fetchImpl, MINECRAFT_PROFILE_URL, {
    headers: authHeaders,
  }, "Minecraft profile check");
  if (!/^[0-9a-f-]{32,36}$/i.test(String(profile.id || ""))
      || !/^[A-Za-z0-9_]{1,16}$/.test(String(profile.name || ""))) {
    throw new Error("Minecraft services did not return a valid Java Edition profile");
  }
  const expiresIn = Math.min(Math.max(Number(minecraft.expires_in) || 14_400, 60), 86_400);
  const activeSkin = profile.skins?.find((skin) => skin.state === "ACTIVE") || profile.skins?.[0];
  return {
    ok: true,
    provider: "microsoft-browser",
    token: accessToken,
    id: String(profile.id).replaceAll("-", "").toLowerCase(),
    name: profile.name,
    skinUrl: typeof activeSkin?.url === "string" ? activeSkin.url : null,
    expiresAt: now() + expiresIn * 1000,
  };
}

async function readBody(request) {
  const chunks = [];
  let length = 0;
  for await (const chunk of request) {
    length += chunk.length;
    if (length > MAX_BODY_BYTES) throw Object.assign(new Error("Microsoft sign-in proof is too large"), { status: 413 });
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw Object.assign(new Error("Microsoft sign-in proof is not valid JSON"), { status: 400 });
  }
}

export async function completeMicrosoftBrowserAuth(request, options = {}) {
  return exchangeXstsForMinecraft(await readBody(request), options);
}
