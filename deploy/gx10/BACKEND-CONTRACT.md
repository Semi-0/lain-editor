# Required backend integration

Deployment is blocked until the runtime owner implements and verifies this contract.
The editor repository supplies the proxy, browser, and deployment templates only.

The `/usr/local/libexec/lain-runtime-protected` launcher must start the real runtime,
read `LAIN_RUNTIME_BEARER_CREDENTIAL_FILE`, bind only `LAIN_RUNTIME_HOST=127.0.0.1`
on `LAIN_RUNTIME_PORT=4780`, and restrict file operations to
`LAIN_RUNTIME_PROJECT_ROOT=/srv/lain/projects`. No permissive defaults are allowed.
An absent, unreadable, empty, or malformed credential must prevent startup.
The service credential is an independently generated random base64url string of
at least 32 characters. It must match the editor's backend credential file.

Before parsing commands or performing **any** operation on `/api`, validate exactly
one `Authorization: Bearer <credential>` value using a constant-time comparison.
Missing, duplicate, malformed, or incorrect credentials return HTTP 401 with a
small generic response. Do not evaluate code, create a session, read/write project
files, poll/dequeue commands, or expose runtime state on authentication failure.
Apply the check to every operation, including inspect, layout, development, file,
workspace, and future operations. Do not introduce an alternate unauthenticated
HTTP endpoint. Reject unsupported methods and paths explicitly.

The runtime receives no browser identity or cookies. All operators intentionally
share one process and project permissions. Authorization and two-factor policy
belong to Authelia. The gateway and backend credentials must be distinct.
Never log bearer headers, source secrets, or credential values. Return 403 only
for an authenticated service's forbidden operation; the editor maps upstream
401 and 403 to a sanitized 502, preserving browser 401 for session expiry.

Required backend evidence, recorded privately against the deployed revision:

1. No header, wrong bearer, malformed bearer, and duplicate authorization each
   return 401 for run, save, inspect, workspace-poll, and development operations.
2. Instrument execution counters, session creation, filesystem reads/writes, and
   poll queues. Every rejected case leaves them untouched; a correct bearer
   executes a controlled program and can inspect it.
3. Missing credential configuration prevents startup. Rotate both consumers,
   restart them, and verify the old credential is rejected without effects.
4. Verify `ss -lntp` and connection attempts from another LAN device: runtime,
   Node, and Authelia are inaccessible directly.

The launcher and credential environment names above are an integration interface,
not a claim that the current runtime already implements them.
