"use strict";

// Browser-owned Microsoft and Xbox OAuth. Only the final short-lived XSTS
// proof crosses the same-origin loopback boundary; Microsoft/Xbox tokens are
// kept in local variables and are never written to browser storage.
globalThis.mcWebMicrosoftAuth = (() => {
  const FLOW_KEY = "mcweb.microsoft.pkce.v1";
  const AUTHORIZE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
  const TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
  const XBOX_USER_URL = "https://user.auth.xboxlive.com/user/authenticate";
  const XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
  const SCOPES = "XboxLive.signin XboxLive.offline_access";
  const FLOW_TTL_MS = 10 * 60 * 1000;

  function base64Url(bytes) {
    let binary = "";
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
  }

  function randomValue(bytes) {
    const value = new Uint8Array(bytes);
    crypto.getRandomValues(value);
    return base64Url(value);
  }

  async function challenge(verifier) {
    return base64Url(new Uint8Array(await crypto.subtle.digest(
      "SHA-256", new TextEncoder().encode(verifier),
    )));
  }

  async function jsonRequest(url, options, label) {
    const response = await fetch(url, { cache: "no-store", ...options });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
      const detail = body.error_description || body.error?.message || body.error || "request rejected";
      throw new Error(`${label} failed (${response.status}): ${detail}`);
    }
    return body;
  }

  async function loadConfig() {
    const response = await fetch("/mcweb/config.json", {
      cache: "no-store", credentials: "same-origin",
    });
    const config = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(`MC-Web config returned HTTP ${response.status}`);
    const auth = config.auth || {};
    if (!auth.microsoftClientId || !auth.microsoftRedirectUri || !auth.microsoftCompletePath) {
      throw new Error("Microsoft sign-in is not configured on this local MC-Web server");
    }
    const redirect = new URL(auth.microsoftRedirectUri);
    if (redirect.origin !== location.origin || redirect.pathname !== "/auth/callback.html") {
      throw new Error("The local server returned an invalid Microsoft redirect URI");
    }
    return auth;
  }

  async function start() {
    const auth = await loadConfig();
    const verifier = randomValue(48);
    const state = randomValue(24);
    sessionStorage.setItem(FLOW_KEY, JSON.stringify({
      verifier,
      state,
      redirectUri: auth.microsoftRedirectUri,
      clientId: auth.microsoftClientId,
      completePath: auth.microsoftCompletePath,
      createdAt: Date.now(),
    }));
    const url = new URL(AUTHORIZE_URL);
    url.search = new URLSearchParams({
      client_id: auth.microsoftClientId,
      response_type: "code",
      redirect_uri: auth.microsoftRedirectUri,
      response_mode: "query",
      scope: SCOPES,
      state,
      code_challenge: await challenge(verifier),
      code_challenge_method: "S256",
      prompt: "select_account",
    });
    location.assign(url.href);
  }

  async function finishCallback() {
    const params = new URLSearchParams(location.search);
    const remoteError = params.get("error");
    if (remoteError) throw new Error(params.get("error_description") || remoteError);
    const flowText = sessionStorage.getItem(FLOW_KEY);
    sessionStorage.removeItem(FLOW_KEY);
    let flow;
    try { flow = JSON.parse(flowText || "null"); } catch { flow = null; }
    const code = params.get("code") || "";
    const state = params.get("state") || "";
    if (!flow || !code || state !== flow.state || flow.createdAt + FLOW_TTL_MS < Date.now()) {
      throw new Error("The Microsoft sign-in response was missing, expired, or did not match this browser");
    }

    const microsoft = await jsonRequest(TOKEN_URL, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        client_id: flow.clientId,
        code,
        redirect_uri: flow.redirectUri,
        grant_type: "authorization_code",
        scope: SCOPES,
        code_verifier: flow.verifier,
      }),
    }, "Microsoft token exchange");

    const xboxUser = await jsonRequest(XBOX_USER_URL, {
      method: "POST",
      headers: { "content-type": "application/json", "x-xbl-contract-version": "1" },
      body: JSON.stringify({
        Properties: {
          AuthMethod: "RPS",
          SiteName: "user.auth.xboxlive.com",
          RpsTicket: `d=${microsoft.access_token}`,
        },
        RelyingParty: "http://auth.xboxlive.com",
        TokenType: "JWT",
      }),
    }, "Xbox user authentication");

    const xsts = await jsonRequest(XSTS_URL, {
      method: "POST",
      headers: { "content-type": "application/json", "x-xbl-contract-version": "1" },
      body: JSON.stringify({
        Properties: { SandboxId: "RETAIL", UserTokens: [xboxUser.Token] },
        RelyingParty: "rp://api.minecraftservices.com/",
        TokenType: "JWT",
      }),
    }, "Xbox security token exchange");
    const userHash = xsts?.DisplayClaims?.xui?.[0]?.uhs;
    if (!userHash || !xsts.Token) throw new Error("Xbox did not return a Minecraft security proof");

    const completed = await jsonRequest(flow.completePath, {
      method: "POST",
      credentials: "same-origin",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ userHash, xstsToken: xsts.Token }),
    }, "Minecraft profile verification");
    if (completed.authenticated !== true) throw new Error("Minecraft profile verification failed");
    location.replace("/?mcweb_auth=success");
  }

  return Object.freeze({ start, finishCallback });
})();
