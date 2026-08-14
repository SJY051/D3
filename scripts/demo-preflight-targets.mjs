const SUPPORTED_JUDGE_ADAPTERS = new Set(["fake", "judge0"]);
const HEADER_NAME = /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/;

export function resolveJudgeAdapter(environment = process.env) {
  const name = environment.D3_JUDGE_ADAPTER ?? "fake";
  return {
    name,
    supported: SUPPORTED_JUDGE_ADAPTERS.has(name),
    judge0Required: name === "judge0",
  };
}

export function resolveJudge0Request(environment = process.env) {
  const rawUrl = environment.JUDGE0_HEALTH_URL ?? "http://localhost:2358/about";
  const rawAllowedOrigin = environment.JUDGE0_ALLOWED_ORIGIN ?? "http://localhost:2358";
  const authenticationHeader = environment.JUDGE0_AUTH_HEADER ?? "X-Auth-Token";
  const authenticationToken = environment.JUDGE0_AUTH_TOKEN;
  let url;
  try {
    const healthUrl = new URL(rawUrl);
    const allowedOrigin = new URL(rawAllowedOrigin);
    const validProtocol = (value) => value.protocol === "http:" || value.protocol === "https:";
    if (
      !validProtocol(healthUrl)
      || !validProtocol(allowedOrigin)
      || healthUrl.username !== ""
      || healthUrl.password !== ""
      || healthUrl.search !== ""
      || healthUrl.hash !== ""
      || allowedOrigin.username !== ""
      || allowedOrigin.password !== ""
      || allowedOrigin.search !== ""
      || allowedOrigin.hash !== ""
      || !["", "/"].includes(allowedOrigin.pathname)
      || healthUrl.origin !== allowedOrigin.origin
    ) {
      return { configured: false, target: "judge0", error: "INVALID_ORIGIN" };
    }
    url = healthUrl.toString();
  } catch {
    return { configured: false, target: "judge0", error: "INVALID_ORIGIN" };
  }
  if (authenticationToken === undefined || authenticationToken.trim() === "") {
    return { configured: false, target: url, error: "MISSING_AUTH_TOKEN" };
  }
  if (
    !HEADER_NAME.test(authenticationHeader)
    || authenticationToken.includes("\r")
    || authenticationToken.includes("\n")
  ) {
    return { configured: false, target: url, error: "INVALID_AUTH_CONFIGURATION" };
  }
  return {
    configured: true,
    url,
    target: url,
    requestInit: {
      redirect: "manual",
      headers: { [authenticationHeader]: authenticationToken },
    },
  };
}
