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

## 1. Infra TLS does not use leaf-certificate pinning

**Overrides:** AI.md:755, 1017 and 1020 — the general TOFU, trust-all, and
certificate-pinning rules.

**Project behaviour:** `HypervisorTrustManagerFactory` does not capture or enforce
leaf-certificate pins. REST `verifySsl=true` uses OkHttp's platform CA-chain and
hostname checks; `verifySsl=false` keeps TLS encryption but accepts any non-empty
peer chain and skips hostname validation. Hypervisor console TLS also remains
encrypted but skips certificate validation. SSH connections independently verify
host keys through the app's known-hosts store. Old `pinnedCertSha256` database fields
remain for backward-compatible schema/import handling and are ignored by TLS clients.

**Why the override holds:** infrastructure providers and self-managed hypervisors can
rotate or replace leaf certificates independently of the SSH server identity. Requiring
users to approve every certificate change creates a recurring interruption and makes
the certificate a misleading identity signal. SSH sessions continue to use their
host-key verification flow. HTTPS-only APIs have no SSH host key on that transport;
their configured `verifySsl` option therefore controls platform certificate checks,
with the default off to support self-signed appliances.

**Accepted residual risk:** an HTTPS-only connection with `verifySsl=false` and a
hypervisor console connection has no server identity check at the TLS layer. This is
the explicit compatibility choice for private infrastructure; credentials and
requests remain encrypted in transit, but a network attacker able to intercept that
connection could impersonate the endpoint.

**Already documented:** IDEA.md records the same behavior and distinction between
SSH host identity and HTTPS certificate validation.

---

## 2. `verifySsl` defaults to off on hypervisor profiles

This remains the accepted per-profile default described in IDEA.md. OCI cloud API
clients default to `verifySsl=true` and therefore use normal platform CA and hostname
verification without pinning an individual leaf certificate.

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

---

## 6. R8 is pinned separately to match Kotlin metadata

**Overrides:** AI.md:586-588 — the Kotlin/AGP/Gradle toolchain follows the
maintained image's compatible versions.

**Project behaviour:** Kotlin remains 2.4.10, AGP 8.13.2, and Gradle 8.14.5 as
declared in `IDEA.md`; `settings.gradle` supplies R8 9.1.31, the first published
patch meeting the 9.1.29 minimum. AGP 8.13.2 bundles
R8 8.13.19, which emits Kotlin metadata parsing errors against Kotlin 2.4 during
release minification. Android's compatibility table requires R8 9.1.29 for
Kotlin 2.4. The override is pinned and applies to provider and F-Droid release
minification.

**Why the override holds:** upgrading AGP would also require changing the pinned
Gradle wrapper and validated build image; using the documented R8 override keeps
the current toolchain while matching Kotlin's metadata format.
