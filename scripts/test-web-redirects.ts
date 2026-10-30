// Browser gate using the pinned upstream Playwright dev dependency and an isolated profile.
import assert from "node:assert/strict"
import { createRequire } from "node:module"
import { resolve } from "node:path"

const root = resolve(import.meta.dir, "..")
const upstream = resolve(process.argv[2] ?? `${root}/work/opencode-v2.2.2`)
const { chromium } = createRequire(`${upstream}/packages/app/package.json`)("@playwright/test")
const executablePath = Bun.which("chromium") ?? Bun.which("google-chrome")
assert.ok(executablePath, "System Chromium/Chrome is required for this explicit browser gate")
const policyProcess = Bun.spawn(["bun", `${root}/scripts/test-web-policy.ts`, upstream, "--print"], { stdout: "pipe" })
const policy = (await new Response(policyProcess.stdout).text()).trim()
assert.equal(await policyProcess.exited, 0)
let hits = 0
const external = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch: () => {
  hits++
  return new Response("document.documentElement.dataset.offorigin='executed'", { headers: { "Content-Type": "application/javascript" } })
} })
const local = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch: (request) => {
  const path = new URL(request.url).pathname
  if (path === "/redirect.js") return Response.redirect(new URL("external.js", external.url), 302)
  if (path === "/local.js") return new Response("document.documentElement.dataset.local='executed'", { headers: { "Content-Type": "application/javascript" } })
  return new Response("<!doctype html><html><head><script src='/redirect.js'></script><script src='/local.js'></script></head><body>Redirect regression</body></html>", {
    headers: { "Content-Type": "text/html", ...(path === "/protected" ? { "Content-Security-Policy": policy } : {}) },
  })
} })
let browser
try {
  browser = await chromium.launch({ executablePath, headless: true, args: ["--no-sandbox", "--disable-dev-shm-usage"] })
  for (const protectedPage of [false, true]) {
    const before = hits
    const context = await browser.newContext()
    const page = await context.newPage()
    await page.goto(new URL(protectedPage ? "protected" : "baseline", local.url).href)
    assert.equal(await page.locator("html").getAttribute("data-local"), "executed", "Local scripts must work")
    assert.equal(await page.locator("html").getAttribute("data-offorigin"), protectedPage ? null : "executed")
    assert.equal(hits > before, !protectedPage, "Protected redirect must never reach the other origin")
    await context.close()
  }
  console.log("Chromium: baseline redirected script executes; actual CSP blocks off-origin fetch/execution and allows local scripts: PASS")
} finally {
  await browser?.close()
  local.stop(true)
  external.stop(true)
}
