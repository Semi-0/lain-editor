# GX10 authenticated LAN deployment

Status: templates only; **not accepted for deployment** until BACKEND-CONTRACT.md
and the real-device checks below pass. No Docker is required. Package versions,
ARM64 packages, SMTP connectivity, DNS, and GX10 service startup must be checked
on the target. No remote changes have been made by adding these templates.

This is a shared runtime/project for explicitly approved operators. There is no
per-user isolation and no public-internet ingress. Run code only within the
intended runtime account's permissions.

## Prepare privately

Install native Node, Java/Clojure required by the backend, Caddy, Authelia, and
systemd on GX10. Verify binary paths in the units (`/usr/bin/node`, `/usr/bin/caddy`,
`/usr/bin/authelia`). Build this repository with `clojure -M:editor/cljs` before
installing a root-owned, read-only release at `/opt/lain-editor`. Keep runtime
code separate from writable projects. Install the backend owner's authenticated
launcher; see [BACKEND-CONTRACT.md](BACKEND-CONTRACT.md).

Create system users/groups `lain-editor`, `lain-runtime`, `authelia`, and `caddy`,
without login shells. Create `/srv/lain/projects` owned by lain-runtime, mode 0700.
Node has no project access; the runtime receives only that writable project root.
For external project tooling, grant access explicitly to the intended project only.
Do not add runtime/editor users to authentication groups or make checkout files
writable by the runtime.

Create `/etc/lain-editor` and its `editor`, `runtime`, `authelia`, `caddy` subdirectories.
Use root ownership and mode 0700 for editor/runtime; root:authelia or root:caddy
with mode 0750 for their respective directories. Create `/var/lib/lain-authelia`
owned by authelia, mode 0700, before starting Node (its sandbox references it).

Use `umask 077` in a private administrative shell. Generate **distinct** gateway,
backend, session, and storage secrets with `openssl rand -hex 32`, redirecting
each directly to its private file. Do not use shell tracing, copy values to chat,
put secrets on command lines, or capture provisioning output in logs. Store:

| File | Owner/mode | Consumer |
| --- | --- | --- |
| `/etc/lain-editor/editor/gateway` | root:root 0600 | Node via systemd credential |
| `/etc/lain-editor/runtime/backend` | root:root 0600 | Node and runtime via systemd credential |
| `/etc/lain-editor/authelia/session` | root:root 0600 | Authelia via systemd credential |
| `/etc/lain-editor/authelia/storage` | root:root 0600 | Authelia via systemd credential |
| `/etc/lain-editor/authelia/smtp` | root:root 0600 | Relay password via systemd credential |

Copy `inputs.example.json` to a private directory **outside Git**. Supply GX10's
private LAN IP, permitted client CIDR, private SMTP relay URL, username, sender,
startup-check recipient, and initial operator identities. SMTP TLS verification
and startup checks remain enabled; trust a private relay CA in the host trust store
if required. Confirm the hostname resolves to the selected private relay.

Generate password hashes interactively using `authelia crypto hash generate argon2`;
put only the hash in the private input file. Check the installed CLI's `--help`.
The renderer provisions only these users in `lain-operators`; all other access is
denied. Empty inputs fail validation:

```
python3 deploy/gx10/render.py /root/lain-private/inputs.json /root/lain-private/rendered-v1
```

The new output directory must have mode 0700. Generated files contain secrets or
password hashes and have mode 0600. Install `Caddyfile` and `gateway-header.caddy`
in `/etc/lain-editor/caddy`, root:caddy 0640. Install `configuration.yml` and
`users.yml` in `/etc/lain-editor/authelia`, root:authelia 0640. Keep raw credentials
root-only; systemd's LoadCredential exposes private copies to each service.

Use the [Authelia TOTP CLI](https://www.authelia.com/reference/cli/authelia/authelia_storage_user_totp_generate/)
for administrator enrollment, against the same config, SQLite database, and storage
encryption credential. Run interactively as authelia with its secret-file environment
set privately. Check `authelia storage user totp generate --help` for the installed
version; select the provisioned username and render its QR code only in the private
terminal. Transfer enrollment to the operator's authenticator privately. Never send
TOTP secrets through Git, command arguments, journal output, screenshots, or chat.
Do not enable self-service password reset or remember-me sessions.

## Network and startup

Point **both** `editor.lain.home.arpa` and `auth.lain.home.arpa` to GX10's LAN IP
in LAN DNS, or add both names to every permitted client's hosts file. Expose TCP 443
only from the configured client subnet in the host firewall. Do not forward router
ports. Keep 4780, 4781, and 9091 loopback-only. Caddy also checks the immediate peer
subnet; no forwarded client address is trusted. HTTP redirects are disabled.

Install the four `*.service` files into `/etc/systemd/system/`, inspect any
conflicting package-provided caddy/authelia units, then run `systemctl daemon-reload`.
Validate Authelia using the same secret-file environment and `authelia config validate`.
Validate Caddy with `caddy validate --config /etc/lain-editor/caddy/Caddyfile --adapter caddyfile`.
Do not print `caddy adapt` output: expanded configuration contains the gateway secret.
The units validate again before startup. Missing imports, secrets, or backend launcher
must prevent startup. Start runtime, Authelia, editor, then Caddy using the supplied
`lain-*` units. Enable only after acceptance. The Caddy configuration follows the
[documented Caddy–Authelia integration](https://www.authelia.com/integration/proxies/caddy/)
with an API-specific status handler: login redirects become 401, forbidden remains
403, and auth service errors fail closed. Websites retain the login redirect.

Caddy creates a local CA. Its public root should be at
`/var/lib/lain-caddy/caddy/pki/authorities/local/root.crt`; verify the actual path.
Verify its fingerprint through a trusted channel and **manually** install this public
certificate into each client's OS/browser trust store. Copy only root.crt, never the
CA private key. Do not disable TLS validation. Back up CA material privately; if the
CA changes, repeat manual trust installation. The Caddy admin API is disabled.

## Verification and acceptance

Local checks: `npm run test:server`, `clojure -M:test`,
`clojure -M:editor/cljs-test` then `node target/lain-editor/tests.js`,
`clojure -M:editor/cljs`, and `npm run test:auth-browser`.
Renderer tests: `python3 deploy/gx10/render_test.py`. Optional real-Caddy routing
checks: `LAIN_CADDY_BINARY=/path/to/caddy npm run test:gateway` (controlled auth/runtime
fixtures; no TLS or real Authelia).
The auth browser suite uses a simulated gateway/API, and does not prove Authelia or
backend enforcement. `npm run test:browser` requires the real configured runtime.
For authenticated deployment testing it accepts `LAIN_EDITOR_BROWSER_STORAGE_STATE`
pointing to a privately saved Playwright session, plus `LAIN_EDITOR_URL`. This file
contains authentication cookies; keep it outside Git with mode 0600 and delete it
after testing. Browser screenshots/reports can contain private project data.

Before acceptance, record these checks against the actual deployed revisions:

- From another permitted LAN device, an unauthenticated website request redirects
  to Authelia; POST `/api` with the exact HTTPS Origin and GET `/auth/session`
  return 401 with **no** login HTML redirect. A client outside the subnet gets 403.
- A password-only session cannot load editor assets, `/auth/complete`, or use API.
  An account outside lain-operators gets 403. A provisioned two-factor operator
  can sign in, run, inspect, save, and use the real-runtime browser acceptance suite.
- Complete every no-effect backend rejection test in BACKEND-CONTRACT.md. Verify
  that proxying a rejected service credential returns sanitized 502, never 401.
- On another LAN device, connect directly to GX10 ports 4780/4781/9091: all fail.
  Locally, requests to Node without the gateway credential return 403.
- Stop `lain-authelia`; ingress must stop or fail closed, including previously
  authenticated requests. Restart Authelia and Caddy; no bypass is allowed.
- With drafts and existing results visible, expire a session (or revoke all sessions
  as below). The next API call shows Session expired, cancels outstanding requests,
  and pauses remote work. Editing and Export workspace still work. Sign in opens
  only the confirmation page. Resume reconnects; interrupted run/extend/save is
  never repeated automatically. An already accepted mutation may have completed
  server-side: inspect before manually retrying.
- Confirm 30-minute inactivity, eight-hour maximum lifetime, and no remember-me.
  Authelia inactivity measures authenticated request activity; editor polling counts
  as activity. The eight-hour maximum still applies to an actively polling tab.

## Operations and rollback

Stop `lain-caddy` before changes to credentials, users, or backend configuration.
Gateway rotation: generate a fresh raw gateway file, rerender a new private output
directory, install its matching Caddy header snippet, restart editor then Caddy.
Backend rotation: replace the shared source credential file, restart runtime and
editor, verify old/missing/wrong bearer no-effect behavior, then restart Caddy.
Never rotate one consumer while allowing ingress to continue.

Revoke an account by removing it from users.yml or its operator group. Restart
Authelia to invalidate **all** in-memory sessions (no Redis is configured), then
restart Caddy and verify the revoked account is denied. Password/TOTP changes alone
must not be assumed to revoke an existing session. For rapid revocation stop ingress
first. Review the installed Authelia CLI for targeted storage/TOTP administration.

Back up users.yml, the SQLite database using SQLite's backup operation, encryption
key, and CA files privately with service-specific permissions. Storage encryption
key rotation requires Authelia's documented storage encryption rotation procedure;
replacing its file alone loses access to encrypted TOTP data. Session secret rotation
and Authelia restart revoke all sessions. Never use debug logs with live credentials.

Restart with `systemctl restart lain-runtime lain-authelia lain-editor`, verify
private services, then `systemctl restart lain-caddy`. Inspect `systemctl status` and
redacted service journals without publishing secrets or expanded proxy config.

Rollback: `systemctl stop lain-caddy` (and disable it if necessary), restore the last
verified release/config privately, validate, and repeat acceptance before reopening
LAN ingress. Local development may run separately on loopback. **Never** remove the
gateway check, change to local mode behind LAN ingress, or bypass Authelia as rollback.
