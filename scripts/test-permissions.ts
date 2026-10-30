// Execute against the pinned checkout, never a reimplementation of its matcher.
import assert from "node:assert/strict"
import { resolve } from "node:path"
import { pathToFileURL } from "node:url"
import { createRequire } from "node:module"

const root = resolve(import.meta.dir, "..")
const upstream = resolve(process.argv[2] ?? `${root}/work/opencode-v2.2.2`)
const { evaluate } = await import(pathToFileURL(`${upstream}/packages/core/src/permission.ts`).href)
const { Info } = await import(pathToFileURL(`${upstream}/packages/schema/src/config.ts`).href)
const { Agent } = await import(pathToFileURL(`${upstream}/packages/schema/src/agent.ts`).href)
const { Schema } = createRequire(`${upstream}/packages/core/package.json`)("effect")
const config = await Bun.file(`${root}/app/src/main/assets/opencode-default.json`).json()
Schema.decodeUnknownSync(Info)(config)
const defaults = Agent.Info.default("build").permissions
function check(action: string, resource: string, expected: string) {
  assert.equal(evaluate(action, resource, defaults, config.permissions).effect, expected, `${action} ${resource}`)
}
check("shell", "pkg install -y git", "ask")
check("external_directory", "/storage/emulated/0/Documents/*", "ask")
check("read", "src/main.kt", "allow")
check("edit", "src/main.kt", "allow")
for (const action of ["read", "edit"]) {
  for (const path of [".env", "nested/.env.production", "nested/.ssh/id_ed25519", ".aws/credentials"]) check(action, path, "ask")
  check(action, ".env.example", "allow")
}
console.log("Pinned V2 config schema and permission behavior: PASS")
