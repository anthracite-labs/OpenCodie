# Existing OpenCode configurations

OpenCodie never migrates an existing `~/.config/opencode/opencode.json`. Upgrading
preserves its original bytes, including malformed files and custom fields. The
maintainer selected manual migration for this security update. Declining or
interrupting these manual steps changes nothing in the app.

New installations use [opencode-default.json](../app/src/main/assets/opencode-default.json).
The recommendations below apply to pinned OpenCode V2 `v2.2.2`. V1's permission
syntax is different; do not replace a custom configuration with a generated file.

## Optional manual update

1. In the Termux terminal, create a unique backup before editing:

   ```sh
   config="$HOME/.config/opencode/opencode.json"
   backup="$config.before-permissions.$(date +%Y%m%d-%H%M%S)"
   cp -p -n -- "$config" "$backup"
   printf 'Backup: %s\n' "$backup"
   ```

2. Open the file in your own editor and review its `permissions` array. Preserve
   all other fields and custom rules. Add only the rules you approve, after any
   broad allow rules that would otherwise override them:

   ```json
   [
     {"action": "shell", "resource": "*", "effect": "ask"},
     {"action": "external_directory", "resource": "*", "effect": "ask"},
     {"action": "read", "resource": "*.env", "effect": "ask"},
     {"action": "read", "resource": "*.env.*", "effect": "ask"},
     {"action": "read", "resource": "*.ssh/*", "effect": "ask"},
     {"action": "read", "resource": "*.aws/*", "effect": "ask"},
     {"action": "read", "resource": "*.config/opencode/auth.json", "effect": "ask"},
     {"action": "read", "resource": "*.env.example", "effect": "allow"},
     {"action": "edit", "resource": "*.env", "effect": "ask"},
     {"action": "edit", "resource": "*.env.*", "effect": "ask"},
     {"action": "edit", "resource": "*.ssh/*", "effect": "ask"},
     {"action": "edit", "resource": "*.aws/*", "effect": "ask"},
     {"action": "edit", "resource": "*.config/opencode/auth.json", "effect": "ask"},
     {"action": "edit", "resource": "*.env.example", "effect": "allow"}
   ]
   ```

   This is an array to merge into `permissions`, not a replacement configuration.
   V2 uses the **last matching rule**. Project/agent/session rules can override
   global asks, and saved “always allow” decisions can satisfy asks. Review those
   decisions too; a configured deny is evaluated before saved allows. Do not
   delete databases or sessions to reset approvals.

3. Restart OpenCodie and check the configuration in OpenCode. Test an ordinary
   project read/edit, shell approval, a `.env` access and an external-directory
   access. Reject unwanted access. If configuration loading fails or a custom
   rule stops behaving as intended, stop and restore the backup you reviewed:

   ```sh
   cp -p -- "$backup" "$config"
   ```

Shell approval is **not a filesystem sandbox**. An approved shell command can
access everything Android grants to this app. Broad shared-storage permission
expands that access. OpenCodie retains its once-per-install automatic Android
permission prompt; declining it leaves coding and document-picker uploads usable.
`termux-setup-storage` explicitly retries. Existing storage files and conflicting
links are preserved; setup preflights every entry before creating links.

For maintainers, `bun scripts/test-permissions.ts` validates the shipped policy
using the pinned V2 schema and actual permission matcher. Device approval behavior
is a separate release gate.
