# TODO

- file:// open flow — user-approved scope: FULL ROUND-TRIP including editing.
  - Core: tap file:// link (or SFTP remote file) → download to dedicated cache (`cacheDir/file-links/`, new `<cache-path>` entry in file_provider_paths.xml) → ACTION_VIEW via FileProvider with MIME from extension (MimeTypeMap; unknown → chooser). Read-only URI grant for view.
  - Editing: allow external edit (ACTION_EDIT / writable grant where the viewer supports it) and re-upload on return — detect local mtime/content change after returning from the editor, prompt "File changed — upload back to {path}?"; handle upload failure by keeping the local copy and re-prompting. No silent uploads.
  - Size gate: prompt before downloading files larger than the threshold; default 20 MB, user-configurable in Settings (new preference).
  - Cache policy: LRU cap 100 MB on cacheDir/file-links/ (evict oldest past cap).
  - SFTP stat for size before download; progress via the existing transfer queue; active tab's session only (no connection → Copy path only).

- Add manifest intent-filters so TabSSH can act as a system-wide handler for ssh:// and sftp:// links tapped in OTHER apps (in-terminal ssh:// links already connect in-app via TerminalLinkClassifier). Requirements: exported activity with ACTION_VIEW + BROWSABLE + DEFAULT for both schemes; NEVER auto-connect — always land on a prefilled confirmation (quick-connect flow); never attach stored keys/passwords to a link-chosen host; sftp:// routes to the SFTP browser with the URL path prefilling the remote directory; parse the URI as untrusted input via TerminalLinkClassifier; custom schemes are exempt from verified App Links, so "Always" default is user-chosen via the resolver sheet when another SSH app also claims the scheme
