import { createHash } from "node:crypto";
import { createServer } from "node:http";

const MAX_BODY_BYTES = 4096;
const MAX_RESULTS = 5;
const WINDOW_MS = 60_000;

export function createCompanyEnrichmentServer(options = {}) {
  const env = options.env ?? process.env;
  const fetchImpl = options.fetchImpl ?? fetch;
  const now = options.now ?? Date.now;
  const minuteCounters = new Map();
  let dailyCounter = { day: utcDay(now()), count: 0 };

  return createServer(async (request, response) => {
    try {
      if (request.method === "GET" && request.url === "/healthz") {
        return sendJson(response, 200, { status: "ok" });
      }
      if (request.method !== "POST" || request.url !== "/v1/company/search") {
        return sendJson(response, 404, { code: "NOT_FOUND" });
      }
      const clientKey = request.headers["x-forwarded-for"]?.split(",")[0]?.trim()
        ?? request.socket.remoteAddress
        ?? "unknown";
      enforceMinuteLimit(minuteCounters, clientKey, now(), positiveInt(env.PER_IP_REQUESTS_PER_MINUTE, 10));
      dailyCounter = enforceDailyBudget(dailyCounter, now(), positiveInt(env.QCC_DAILY_BUDGET, 1000));
      const body = await readJsonBody(request);
      const input = validateSearchInput(body);
      const matches = await queryQichacha(input.query, env, fetchImpl, now);
      return sendJson(response, 200, { provider: "qichacha", matches });
    } catch (error) {
      const safe = safeError(error);
      return sendJson(response, safe.status, { code: safe.code });
    }
  });
}

export async function queryQichacha(query, env, fetchImpl, now = Date.now) {
  const appKey = requiredSecret(env.QCC_APP_KEY, "QCC_APP_KEY_MISSING");
  const secretKey = requiredSecret(env.QCC_SECRET_KEY, "QCC_SECRET_KEY_MISSING");
  const timespan = Math.floor(now() / 1000).toString();
  const token = createHash("md5").update(`${appKey}${timespan}${secretKey}`).digest("hex").toUpperCase();
  const url = new URL("https://api.qichacha.com/FuzzySearch/GetList");
  url.searchParams.set("key", appKey);
  url.searchParams.set("searchKey", query);
  const upstream = await fetchImpl(url, {
    headers: { Token: token, Timespan: timespan, Accept: "application/json" },
    signal: AbortSignal.timeout(8000),
  });
  if (!upstream.ok) throw gatewayError("QCC_UPSTREAM_FAILURE", 502);
  const payload = await upstream.json();
  if (String(payload.Status ?? "200") !== "200") throw gatewayError("QCC_BUSINESS_FAILURE", 502);
  const rows = Array.isArray(payload.Result) ? payload.Result.slice(0, MAX_RESULTS) : [];
  return rows.map((row) => normalizeQccRow(query, row)).filter(Boolean);
}

export function normalizeQccRow(query, row) {
  if (!row || typeof row !== "object") return null;
  const canonicalName = safeText(row.Name, 200);
  const providerRecordId = safeText(row.KeyNo ?? row.CreditCode, 160);
  if (!canonicalName || !providerRecordId) return null;
  const normalizedQuery = normalizeCompanyName(query);
  const normalizedName = normalizeCompanyName(canonicalName);
  const exact = normalizedQuery === normalizedName;
  const contains = normalizedName.includes(normalizedQuery) || normalizedQuery.includes(normalizedName);
  return {
    providerRecordId,
    canonicalName,
    creditCode: safeText(row.CreditCode, 32),
    registrationStatus: safeText(row.Status, 40),
    registeredAddress: safeText(row.Address, 300),
    confidence: exact ? 0.98 : contains ? 0.9 : 0.7,
    matchReasons: [exact ? "企业名称一致" : contains ? "企业名称高度相似" : "企业名称可能相关"],
  };
}

function validateSearchInput(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) throw gatewayError("INVALID_REQUEST", 400);
  const allowed = new Set(["requestId", "query"]);
  if (Object.keys(body).some((key) => !allowed.has(key))) throw gatewayError("INVALID_REQUEST", 400);
  if (typeof body.requestId !== "string" || !/^company-[a-f0-9]{24}$/.test(body.requestId)) {
    throw gatewayError("INVALID_REQUEST", 400);
  }
  const query = safeText(body.query, 80);
  if (!query || query.length < 2 || /[\u0000-\u001f]/.test(query)) throw gatewayError("INVALID_REQUEST", 400);
  return { query };
}

async function readJsonBody(request) {
  const type = String(request.headers["content-type"] ?? "");
  if (!type.toLowerCase().startsWith("application/json")) throw gatewayError("INVALID_CONTENT_TYPE", 415);
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) throw gatewayError("REQUEST_TOO_LARGE", 413);
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw gatewayError("INVALID_JSON", 400);
  }
}

function enforceMinuteLimit(counters, key, timestamp, limit) {
  const bucket = Math.floor(timestamp / WINDOW_MS);
  const current = counters.get(key);
  const next = current?.bucket === bucket ? { bucket, count: current.count + 1 } : { bucket, count: 1 };
  counters.set(key, next);
  if (next.count > limit) throw gatewayError("RATE_LIMITED", 429);
}

function enforceDailyBudget(counter, timestamp, budget) {
  const day = utcDay(timestamp);
  const next = counter.day === day ? { day, count: counter.count + 1 } : { day, count: 1 };
  if (next.count > budget) throw gatewayError("DAILY_BUDGET_EXHAUSTED", 429);
  return next;
}

function utcDay(timestamp) {
  return new Date(timestamp).toISOString().slice(0, 10);
}

function normalizeCompanyName(value) {
  return value.trim().toLowerCase().replace(/\s+/g, "");
}

function safeText(value, maxLength) {
  if (typeof value !== "string") return null;
  const text = value.trim();
  return text && text.length <= maxLength ? text : null;
}

function requiredSecret(value, code) {
  if (typeof value !== "string" || value.length < 8) throw gatewayError(code, 503);
  return value;
}

function positiveInt(value, fallback) {
  const parsed = Number.parseInt(value ?? "", 10);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}

function gatewayError(code, status) {
  return Object.assign(new Error(code), { code, status });
}

function safeError(error) {
  return {
    code: typeof error?.code === "string" ? error.code : "INTERNAL_FAILURE",
    status: Number.isInteger(error?.status) ? error.status : 500,
  };
}

function sendJson(response, status, body) {
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
  });
  response.end(JSON.stringify(body));
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const port = positiveInt(process.env.PORT, 8787);
  createCompanyEnrichmentServer().listen(port, "127.0.0.1");
}
