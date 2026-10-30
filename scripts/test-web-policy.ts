// Execute the real middleware from the pinned, patched server.
import assert from "node:assert/strict"
import { resolve } from "node:path"
import { pathToFileURL } from "node:url"
import { createRequire } from "node:module"

const upstream = resolve(process.argv.slice(2).find((arg) => !arg.startsWith("--")) ?? `${import.meta.dir}/../work/opencode-v2.2.2`)
const { localContentSecurity } = await import(pathToFileURL(`${upstream}/packages/server/src/process.ts`).href)
const require = createRequire(`${upstream}/packages/server/package.json`)
const { Effect } = require("effect")
const { HttpServerResponse } = require("effect/http")
for (const status of [200, 302, 404, 500]) {
  const response = await Effect.runPromise(localContentSecurity(Effect.succeed(HttpServerResponse.text("body", { status }))))
  const policy = response.headers["content-security-policy"]
  assert.ok(policy.includes("script-src 'self'"))
  assert.ok(policy.includes("connect-src 'self' ws://127.0.0.1:4096"))
  assert.ok(policy.includes("object-src 'none'"))
  assert.equal(response.status, status)
  if (process.argv.includes("--print")) { console.log(policy); break }
}
if (!process.argv.includes("--print")) console.log("Pinned server CSP middleware: PASS")
