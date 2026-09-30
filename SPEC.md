# TabSSH Android — Project Rule Overrides

> Project-specific overrides to AI.md and the global `~/.claude/CLAUDE.md`.
> **Precedence: SPEC.md > AI.md > global CLAUDE.md.** This file exists only to
> record rules that *must* differ from those specifications. Anything not listed
> here follows AI.md unchanged.
>
> Every entry cites the rule it overrides and the evidence for the deviation, so a
> reviewer can re-verify rather than trust the summary. Update this file whenever
> one of these behaviours changes — and delete the entry when the deviation ends.

---

## 1. Certificate pinning ships without a backup pin

**Overrides:** AI.md:1020 — *"Certificate pinning is optional and per-host, with a
documented rotation plan (backup pin) — never pin without one."*

**Project behaviour:** `HypervisorTrustManagerFactory` stores exactly one SHA-256 pin
per host, both in memory and in the profile columns
(`HypervisorProfile.pinnedCertSha256`, `VncHost.pinnedCertSha256`). There is no second
pin slot and no pin-history storage; `grep` for `backup.?pin|secondary.?pin|pin_history`
across `app/src/main` returns nothing.

**Why the override holds:** rotation is handled by re-validation rather than by a
pre-staged second pin. On a pin mismatch the trust manager re-checks the system CA
before treating the change as a MITM
(`HypervisorTrustManagerFactory.kt:288-313`): a certificate that still chains to a
system CA is re-pinned silently — this is the case that would otherwise strand a user
on a legitimate rotation of a multi-tenant endpoint such as an OCI load-balanced API
host. Self-signed and private-CA hosts never reach that branch, so they keep the
strict confirm-on-change prompt. The security property AI.md's backup pin is
protecting — never silently trusting a certificate that is not genuinely the host's —
is preserved by that split; only the storage mechanism differs.

**If this ever changes:** a backup pin means accepting the presented cert if *either*
pin matches, which is strictly weaker than the current re-validation check. Adding one
requires re-deriving the threat model above, not just adding a column.

---

## 2. `verifySsl` defaults to off on hypervisor profiles

**Overrides:** AI.md:755 and AI.md:1017 — *"TOFU with explicit user confirmation on
change; never silent trust-all."*

**Project behaviour:** `HypervisorProfile.verifySsl` defaults to `false`. With
verification off, the first certificate seen is pinned silently, with no prompt
(`HypervisorTrustManagerFactory.kt:140-148`). `OciApiClient` deliberately inverts this
default to `true`, because OCI's public CA endpoint needs it.

**Why the override holds:** hypervisor consoles are overwhelmingly deployed on
private LANs with self-signed certificates; a first-use prompt that rejects them
makes the product unusable for its primary audience. The pin is still captured and
still enforced on every subsequent connection, and a *changed* certificate always
prompts — the "silent" part is scoped to first contact only, never to a change.
The bypass is per-entity (a profile field), never global, and a warning is logged at
`installTrust`.

**Already documented:** IDEA.md:232 states this as an accepted design decision and
names the pin as the compensating control. This entry restates it so the AI.md
contradiction resolves explicitly rather than being left implicit.

---

## 3. Cleartext traffic is permitted by wildcard, not scoped to private ranges

**Overrides:** AI.md:1019 — the local-network exception permits a cleartext exception
*"scoped to private/link-local IP ranges only (RFC 1918 + link-local, never a
wildcard, never a public-internet domain)"* — and AI.md:1017's
`cleartextTrafficPermitted="false"` default.

**Project behaviour:** `app/src/main/res/xml/network_security_config.xml` sets

```xml
<base-config cleartextTrafficPermitted="true">
```

A **base-config** wildcard: cleartext is permitted to every host, including
public-internet domains, in **all build variants** (there is no `app/src/debug/res`
override, so this is not a debug-only exception either).

**Why the override holds:** cleartext must stay permitted at all, because
hypervisor and cloud endpoints are user-configured and may be plain-HTTP consoles on
a private network. The RFC 1918 scoping AI.md prescribes, however, cannot express
this app's requirement: those endpoints are reached by *hostname* as typed by the
user, and a user-configured endpoint may resolve to a public address. Scoping to
literal IP ranges would break exactly the configurations the exception exists to
serve.

**Accepted residual risk:** a cleartext request to a public-internet host is not
blocked at the platform layer. Mitigation is at the product layer rather than the
network layer — the user must explicitly configure the host, and no first-party
TabSSH service is ever reached over cleartext.

**Already documented:** IDEA.md:233 and the XML's own comment record the departure
from the PART 9 ban. Neither addresses the scoping clause; this entry does.

---

## 4. `make check` runs Android Lint, not `ktlint`/`detekt`

**Overrides:** AI.md:265 — *"`ktlint` … formats every file; `detekt` runs in `make
check` for static analysis … both gate every commit"* — repeated at AI.md:286,
AI.md:434, and the `check` row of the PART 4 Make-target table (AI.md:643).

**Project behaviour:** neither `ktlint` nor `detekt` appears anywhere in the
repository — not in `app/build.gradle`, not in any `.kts`, not in the `Makefile`.
`Makefile:68` runs:

```
./gradlew kspDebugKotlin compileDebugKotlin lintDebug testDebugUnitTest processDebugResources
```

The static-analysis gate is Android Lint alone, configured at `app/build.gradle:316+`
with `abortOnError true` and the security checks explicitly promoted — `SecureRandom`,
`TrustAllX509TrustManager`, `BadHostnameVerifier`,
`SSLCertificateSocketFactoryCreateSocket` — precisely the classes of defect `detekt`
would cover here.

**Why the override holds:** Android Lint is the Android-native equivalent and, unlike
`detekt`, it understands the Android framework, manifest, and resources. The
deliberate tuning in `app/build.gradle` (notably the comment recording that
`checkOnly` was previously used and silently disabled `NewApi` and hundreds of other
checks) shows the gate is actively curated, not a default that was merely accepted.

**Consequence to be aware of:** complexity, unused-code, and magic-number findings —
the specific categories AI.md assigns to `detekt` — are **not** currently gated. The
build's own comment history is the only guard against the curated `lint` set silently
narrowing again.

**Reconsider if:** the spec is ever enforced mechanically rather than by reviewer
reading, at which point the missing categories become an actual gap.

---

## 5. Commit path and pre-commit gate

**No override** — recorded to prevent a misread of the global rules. Two things
here look like deviations and are not.

**Commit format:** the global `~/.claude/CLAUDE.md` directs that `COMMIT_MESS` be
written in the upstream's commit-log style, "never our emoji format" — but that
rule sits under **External Contributions** and applies to forks, PRs, and fixes to
third-party repos we do not own. TabSSH is our own project, so the emoji format
applies: `{emoji} Title (≤64 chars) {emoji}` + blank line + body + `- path: change`
bullets per file, per `gitcommit_conventions.md`, matching the repo's history.

**Standing gate override:** `enforce-test-lint-gate.sh` intermittently fails to
register a genuinely passing `make check` / `make test` in this project (upstream
`anthropics/claude-code#6305`). When that happens *and* `make check` or `make test`
has actually been run and exited 0 in the current session, run
`TEST_LINT_GATE_OVERRIDE=1 gitcommit --dir … all` without asking first.

This relaxes nothing else: the pass must be real and verified, never a way to skip a
failing or not-yet-run test/lint pass, and every other confirm-before-destructive-op
rule applies unchanged. Full text: `.claude/memory/gitcommit_gate_override.md`.

---

## Verified compliant — no override needed

Checked against AI.md and found to match; recorded here so a future reviewer does
not re-open them:

| Area | Rule | Evidence |
|---|---|---|
| Docker image | `casjaysdev/android:latest` (AI.md:596) | `Makefile:11`, IDEA.md:47 |
| SDK levels | minSdk 24 / targetSdk 34 / compileSdk 35 | `app/build.gradle:76,100,101` |
| App identity | `io.github.tabssh`, frozen | `app/build.gradle:93` |
| DI | manual DI on the `Application` (AI.md:475) | no Hilt/Koin in `build.gradle`; no service locators |
| HTTP client | one client app-wide (AI.md:1010) | OkHttp only, 26 files, zero Ktor/Retrofit |
| Clipboard clear | ownership-checked, never blind (AI.md:741) | `ClipboardHelper.kt:117-133`, label-matched |
| Logger masking | credential patterns sanitized (AI.md:757) | `Logger.kt:147-152` |
| Storage permission | file-manager-class exception, lazy + justified (AI.md:486) | manifest + IDEA.md:243 |
| No Play Services | never a dependency (AI.md) | `build.gradle:522` |
| Exported components | individually justified, extras validated | `LinkHandlerActivity.kt:54` |
| Licensing | attribution for borrowed code | `LICENSE.md:222,226,346` |
| Host toolchain | never install SDK/Gradle/JDK on host (AI.md:280) | enforced by PreToolUse hook |
