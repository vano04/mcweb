"use strict";

globalThis.mcWebMicrosoftAuth.finishCallback().catch((error) => {
  const message = error?.message || String(error);
  const status = document.getElementById("auth-status");
  if (status) status.textContent = message;
  const url = new URL("/", location.origin);
  url.searchParams.set("mcweb_auth", "error");
  url.searchParams.set("message", message.slice(0, 500));
  setTimeout(() => location.replace(url.href), 1200);
});
