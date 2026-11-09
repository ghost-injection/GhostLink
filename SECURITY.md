# SECURITY MODEL

GhostLink is a personal remote-mirroring build. This document states exactly what
it protects, what it assumes, and what it does not defend against. Read it before
you deploy it anywhere you care about.

## Trust boundaries

| Boundary | Protected by |
|---|---|
| Network transport | WireGuard, via Tailscale, between the two devices only |
| Peer identity | Expected peer IP check on both helper and receiver |
| Request authenticity | Random 256-bit controller token, checked per request |
| Screen/control channel | Paired ADB TLS through the tunnel |
| Bind surface | Helper binds only its tailnet IPv4:47831; receiver only its configured tailnet IPv4:47832 |

The helpers speak HTTP inside the encrypted tunnel. They refuse public and LAN
bind addresses. That refusal is deliberate: plaintext HTTP is only acceptable
because it never leaves WireGuard.

## What the APK contains

A personalized build embeds the initial controller token so a fresh install is
ready to use. That also means:

**Do not publish a personalized APK.** Anyone holding it holds the token. Combine
that with tailnet reachability and they can talk to your helper. Build your own
APK with your own token.

## Secrets inventory

| Secret | Lives in | Never do this |
|---|---|---|
| Controller token | `config.json`, personalized APK | Commit, paste, screenshot, share |
| Signing keystore | `.tools/ghostlink.jks` | Distribute or back up to a public place |
| Tailnet addresses | `config.json`, device settings | Publish alongside the token |
| Device serial | `config.json` | Publish |
| ADB pairing keys | User profile `~/.android` | Copy between machines you do not own |

## What this build does not protect against

- **A compromised tailnet.** If an attacker is already on your Tailscale network
  with ACL access to those ports, the token is the only remaining gate.
- **A compromised PC.** The controller can enable wireless debugging. Treat the
  PC as privileged.
- **Physical device access.** Nothing here resists someone holding the unlocked
  phone.
- **Malicious Android OEM process management.** Samsung's battery manager and
  force-stop behaviour can kill the helper. That is availability, not security.
- **Token reuse across builds.** Rotate the token if a build leaks.

## Permissions requested and why

| Permission | Purpose |
|---|---|
| `WRITE_SECURE_SETTINGS` | Lets the PC re-enable wireless debugging after a reboot or network change |
| Foreground service | Keeps the helper responsive with an ongoing notification |
| Battery optimisation exemption | Prevents the OS from silently killing the helper |
| Network access | Tailscale connectivity only |

No root. No legacy ADB on 5555. No inbound router rules.

## Reporting

This is a personal project, not a supported product. If you find a real flaw,
open an issue without live tokens, addresses or serials in the text.

## Hardening checklist before you ship your own build

- [ ] Generate a fresh 256-bit token, never reuse one from a build you shared
- [ ] Create your own keystore, never reuse one from a build you shared
- [ ] Keep `config.json` out of git, out of screenshots, out of chat
- [ ] Restrict Tailscale ACLs to the two devices and the two ports
- [ ] Rebuild the APK after rotating the token
- [ ] Revoke old pairings in Android wireless debugging when retiring a PC
