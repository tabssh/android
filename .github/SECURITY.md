# Security Policy

Thanks for taking the time to disclose security issues responsibly.

## Reporting a Vulnerability

**Use GitHub's private vulnerability reporting** on this repository:

👉 [Report a vulnerability](https://github.com/tabssh/android/security/advisories/new)

This sends the report directly to the maintainers — it's not visible to the
public until we publish an advisory. **Please do not file public issues for
security bugs.**

If you can't use GitHub for some reason, the fallback is the maintainer
listed in the repo's commit history; please mark the subject line
`[security]`.

## What to Include

A useful report typically has:

- **Description** of the vulnerability and its impact
- **Reproduction steps** — exact APK arch + version, Android OS version,
  device model, and the smallest possible setup that triggers it
- **Proof-of-concept** if you have one — code, traffic capture, or log
  excerpt (redact credentials)
- **Suggested fix** if you have an idea (not required)

## What's In Scope

- The TabSSH mobile (Android) codebase in this repository
- Anything that compromises **credential confidentiality** — stored
  passwords, SSH keys, Android Keystore entries, sync-blob encryption
- Anything that compromises **session integrity** — host-key bypass,
  MITM, injection into the terminal stream
- Anything that compromises **app integrity** — privilege escalation,
  arbitrary code execution, FLAG_SECURE bypass on sensitive activities

## What's Out of Scope

- Vulnerabilities in upstream dependencies (JSch, BouncyCastle, AndroidX,
  etc.) — please report those upstream; we'll coordinate once a patched
  version is available
- Issues that require physical device access *and* an unlocked screen
- Self-XSS in fields the user controls themselves
- Missing security headers on the marketing site (`tabssh.github.io`) —
  report to that repo
- Theoretical attacks without a demonstrated exploit path

## Response SLA

We aim to:

- **Acknowledge** within **5 business days**
- **Triage** (confirm + severity assessment) within **10 business days**
- **Patch** critical/high issues within **30 days** of triage
- **Coordinate disclosure** — we'll let you know when a fix is in review
  and target a public advisory date together

## Attribution

If you'd like credit, we'll list you in the published advisory and the
release notes. Pseudonymous credit is fine — just tell us how you'd like
to be referenced. If you'd rather stay anonymous, that's also fine.

## Hall of Fame

Thank you to everyone who has reported issues responsibly:

*(no advisories published yet — be the first!)*
