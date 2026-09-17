# tabssh

Read `AI.md`, `IDEA.md`, and `SPEC.md` before acting on this project.

## Standing scope rule

Fix issues as you find them. Everything found while working on this project is
in scope — no need to ask before fixing, and no need to stop at the boundary of
the task that surfaced the problem. This stands until the user says otherwise.

Unchanged by this rule: no regressions, the commit path is `gitcommit`, and
destructive or irreversible operations still need explicit confirmation.

## Terminology

The user names a **class of protocols**, not one protocol, unless a specific
protocol is named. Scope every fix, feature, and review to the whole class.

- **terminal** — the character-stream class: SSH, telnet, mosh, serial, and any
  future text transport. Anything whose session renders into a `TerminalView`.
  Implemented today: SSH, telnet, mosh. No local shell exists in the app; if one
  is ever added it belongs to this class.
- **gui** (also **GUI**) — the framebuffer class: VNC, RFB, SPICE, and RDP if it
  is ever added. The user prefers "gui" over "vnc" for the class, but "vnc" may
  still be used for it; when "vnc" appears, decide from context whether it means
  the class or the VNC protocol specifically, and ask if that is not clear.
- A named protocol (**ssh**, **telnet**, **mosh**, **spice**, **rfb**) is narrow
  scope: that protocol only.
- A generic class word with no protocol named means the protocol is unknown or
  irrelevant — trace it to the actual protocol in code before scoping the work.
