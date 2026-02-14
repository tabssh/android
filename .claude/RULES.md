# Claude Session Rules for TabSSH Android

## Core Rules

1. **Never guess or assume** - Always ask if unsure about anything
2. **Question mark = question** - When user ends with `?`, answer or clarify rather than execute
3. **Always search code first** - Read and understand existing code before making any changes

## Question & Input Rules

1. **Question mark = question** - Answer the question, don't execute a command
2. **Multiple questions = wizard** - Use appropriate layout for multi-step input
3. **Multichoice = numbered/lettered/checkboxes** - Use whichever format is appropriate:
   - **Numbers (1, 2, 3)** - For ordered/sequential choices
   - **Letters (a, b, c)** - For unordered single-select
   - **Checkboxes (☐/☑)** - For multi-select options

## TODO Management

- **Always use `TODO.AI.md`** as the task tracker
- **Keep it synced** with current work progress
- Update status as tasks are started, completed, or blocked

## Git Rules

- **NO `git commit`** - Do not run git commit commands
- **NO `git push`** - Do not run git push commands
- **Save commit message to `.git/COMMIT_MESS`** instead
- **Commit message must match actual status** - Based on real `git status` and `git diff` output
- Update `.git/COMMIT_MESS` as work progresses

## Version Rules

- **Version is in `release.txt`** - Single source of truth
- **DO NOT modify `release.txt`** - User controls version
- **Version changed via:** Git tag or user editing release.txt
- **Current version:** 1.0.0 (pinned)

## Mobile-First Design Rules

- **Target:** 4.1" screens minimum
- **Design approach:** Small screens first → scale up for tablets
- **Touch targets:** 48dp minimum
- **No mouse-dependent interactions** (no VNC for VM console)
- **Single-column layouts** that expand on larger screens

## Build Rules

- **Always use Docker** for builds (`make build`, `make release`, etc.)
- Never use local Gradle directly

## Screenshot Viewing

- **Use `curl`** to view remote screenshots

## Kotlin Coding Conventions

Follow the [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html):

- **4 spaces** for indentation (no tabs)
- **120 character** line limit
- **Meaningful variable names** - Self-documenting code
- **Document public APIs with KDoc** - All public methods/classes
- **Use `@SuppressLint` sparingly** - Always include justification comment

## Code Style Examples

```kotlin
/**
 * Connects to the SSH server using the provided profile.
 *
 * @param profile The connection profile containing server details
 * @param timeout Connection timeout in milliseconds
 * @return True if connection successful, false otherwise
 * @throws SSHConnectionException if connection fails with error
 */
fun connectToServer(profile: ConnectionProfile, timeout: Long = 30000L): Boolean {
    // Implementation
}
```

## File Locations

- **TODO file:** `TODO.AI.md` (project root) - Issue tracking
- **Plan file:** `PLAN.md` (project root) - Implementation details
- **Commit message:** `.git/COMMIT_MESS`
- **Rules file:** `.claude/RULES.md` (this file)
- **Version file:** `release.txt` (DO NOT MODIFY)
- **Temp files:** `/tmp/tabssh-android/`

---

*Last Updated: 2026-02-11*
