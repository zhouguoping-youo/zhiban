import assert from "node:assert/strict";
import test from "node:test";
import { normalizeQccRow, queryQichacha } from "./server.mjs";

test("normalizes only the public company fields used by Android", () => {
  const normalized = normalizeQccRow("星河科技", {
    KeyNo: "qcc-1",
    Name: "星河科技有限公司",
    CreditCode: "91310000TEST",
    Status: "存续",
    Address: "上海市徐汇区",
    OperName: "不应返回的法人姓名",
  });

  assert.equal(normalized.canonicalName, "星河科技有限公司");
  assert.equal(normalized.confidence, 0.9);
  assert.equal("OperName" in normalized, false);
});

test("qichacha credentials stay in headers and are not returned", async () => {
  let captured;
  const fetchImpl = async (url, options) => {
    captured = { url, options };
    return {
      ok: true,
      json: async () => ({ Status: "200", Result: [{ KeyNo: "qcc-1", Name: "星河科技" }] }),
    };
  };

  const result = await queryQichacha(
    "星河科技",
    { QCC_APP_KEY: "test-app-key", QCC_SECRET_KEY: "test-secret-key" },
    fetchImpl,
    () => 1_700_000_000_000,
  );

  assert.equal(result.length, 1);
  assert.equal(captured.url.searchParams.get("key"), "test-app-key");
  assert.equal(captured.url.searchParams.get("searchKey"), "星河科技");
  assert.match(captured.options.headers.Token, /^[A-F0-9]{32}$/);
  assert.equal(JSON.stringify(result).includes("test-secret-key"), false);
});
