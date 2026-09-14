# SFTP Subsystem Audit — Findings

Scope: SFTPManager, TransferTask, SCPClient, RemoteFileOpener,
RemoteFileEditorActivity, SFTPActivity, FileAdapter, LocalFileSource,
FileOpenPolicy, FileProvider config. Ordered BLOCKER → MAJOR → MINOR.

### [BLOCKER] [CONFIRMED] Upload resume corrupts every editor save-back that grows a file
- Where: `app/src/main/java/io/github/tabssh/sftp/SFTPManager.kt:654-676`, callers `app/src/main/java/io/github/tabssh/ui/activities/RemoteFileEditorActivity.kt:284`, `app/src/main/java/io/github/tabssh/sftp/RemoteFileOpener.kt:230`
- What: `performUpload` treats any existing remote file smaller than the local file as an interrupted transfer: `if (remoteAttrs.size < localFile.length())` → `channel.put(task.remotePath, ChannelSftp.RESUME)` + `skipFully(inputStream, startOffset)`. But the in-app editor (`saveFile`) and the external-viewer upload-back path upload NEW content to an existing path. Resume mode skips the first `remoteSize` bytes of the new content and appends the remainder after the OLD remote prefix.
- Failure scenario: remote file is `AAAA` (4 bytes). User opens it in RemoteFileEditorActivity, changes it to `BBBBBB` (6 bytes), taps Save. Upload sees remote 4 < local 6 → resume → remote becomes `AAAABB`. Reported as success; file silently corrupted. Same for RemoteFileOpener's "upload changed file back" prompt.
- Fix: resume must be opt-in per transfer, not inferred from sizes. Add an `allowResume: Boolean` parameter (default false) to `uploadFile`/`performUpload`; only the explicit "resume interrupted transfer" path passes true. Editor save-back and upload-back must always use `ChannelSftp.OVERWRITE`.

### [BLOCKER] [CONFIRMED] Download resume + deterministic cache name corrupts opened files
- Where: `app/src/main/java/io/github/tabssh/sftp/SFTPManager.kt:761-787`, `app/src/main/java/io/github/tabssh/sftp/RemoteFileOpener.kt:100-101` (`FileOpenPolicy.cacheFileName` is deterministic per remote path)
- What: `performDownload` treats an existing local file smaller than the remote as a partial download: `if (localSize < task.totalBytes)` → skip `localSize` remote bytes and append with `FileOutputStream(localFile, true)`. RemoteFileOpener always downloads to the SAME cache filename for a given remote path and does not delete a previous copy first.
- Failure scenario: user opens `/etc/app.conf` (100 bytes) → cached copy, 100 bytes. File is later edited on the server to 400 bytes with different content. User opens it again: localSize 100 < total 400 → download "resumes" → opened file = stale first 100 bytes + new bytes 100-400. Viewer shows a corrupted mix; if the user edits and uploads back, the corruption is written to the server.
- Fix: same opt-in resume flag for downloads; RemoteFileOpener (and the editor's load path) must request a fresh full download (delete/truncate the cache file first or pass `allowResume = false`). If resume is kept anywhere, it must also compare remote mtime against the local partial before appending.

### [BLOCKER] [CONFIRMED] Batch upload deletes the source file mid-transfer and reports success for transfers that merely started
- Where: `app/src/main/java/io/github/tabssh/ui/activities/SFTPActivity.kt:950-966`
- What: `SFTPManager.uploadFile`/`uploadDirectory` are asynchronous — they launch into `transferScope` and return the `TransferTask` immediately. `uploadSelectedFiles` calls them, then in `finally { cleanupMaterialized(entry, materialized) }` deletes the materialized temp (`materialized.parentFile?.deleteRecursively()` at line 932) while the transfer coroutine is still reading it, then does `count++` unconditionally and toasts "N uploaded".
- Failure scenario: select 3 SAF-backed files, tap Upload. Temp copies are deleted within milliseconds of the transfers starting → uploads fail with FileNotFoundException (or truncate) inside `transferScope`, errors are only visible via TransferListener (none registered) → user sees "3 uploaded", server has 0-byte/absent files. Also, for direct (non-SAF) `File` entries `materializeForUpload` returns the real file's parent? — if the materialized file IS a temp copy, data loss is on the copy only, but the false success toast stands regardless.
- Fix: await each transfer to a terminal state (reuse the `awaitTransfer` polling pattern from RemoteFileOpener, or make suspend variants that return `TransferResult`) before `cleanupMaterialized` and before counting it as a success; count only `TransferResult.Success`.

### [BLOCKER] [CONFIRMED] Batch download copies incomplete files into the SAF tree and deletes the in-flight destination
- Where: `app/src/main/java/io/github/tabssh/ui/activities/SFTPActivity.kt:1058-1079`
- What: same async misuse in `downloadSelectedFiles`: `downloadFile`/`downloadDirectory` return immediately; the SAF branch then runs `copyLocalFileIntoSaf(temp, saf, file.name)` on a file the transfer has barely started writing, then `temp.parentFile?.deleteRecursively()` removes the destination out from under the still-running transfer. `count++` is unconditional.
- Failure scenario: with a SAF local pane, select a 50 MB remote file → Download. An empty/partial temp is copied into the user's chosen SAF folder, the temp dir is deleted (transfer then errors or writes to a recreated orphan path), toast says "1 downloaded". User's folder contains a 0-byte or truncated file presented as a successful download.
- Fix: await each TransferTask to a terminal state; only on `TransferResult.Success` copy into SAF and delete the temp; surface per-file failures.

### [BLOCKER] [CONFIRMED] Multi-tab: Open/Edit and SCP upload target the ORIGINAL intent connection, not the active tab
- Where: `app/src/main/java/io/github/tabssh/ui/activities/SFTPActivity.kt:1412` (openOrEditRemoteFile), `app/src/main/java/io/github/tabssh/ui/activities/SFTPActivity.kt:1005` (uploadSelectedFilesViaScp)
- What: both resolve the connection via `intent.getStringExtra(EXTRA_CONNECTION_ID)` — the connection the activity was launched with — while Wave 8.5 tabs reseat `sftpManager`/`currentRemotePath` per tab. Per-item `uploadFile`/`downloadFile` correctly use the active tab's manager; these two paths do not.
- Failure scenario: open SFTP for server A, add a tab for server B, switch to B, browse to `/etc/`, long-press `app.conf` → Edit → save. RemoteFileEditorActivity gets server A's connection id with server B's path: the file `/etc/app.conf` on server A is overwritten with content intended for B (or created there). Same class of cross-server write for SCP upload: local files land on server A at B's `currentRemotePath`.
- Fix: resolve the connection id from the active `SftpTab` (the same source the per-item transfer paths use), not from the launch intent.

### [MAJOR] [CONFIRMED] Pause is a no-op — bytes keep flowing while the UI shows PAUSED
- Where: `app/src/main/java/io/github/tabssh/sftp/SFTPManager.kt:860-899` (`transferWithProgress` checks only `task.isCancelled()`), `app/src/main/java/io/github/tabssh/sftp/TransferTask.kt:185` (pause() just sets a flag), `app/src/main/java/io/github/tabssh/ui/activities/SFTPActivity.kt:1655` (pauseTransfer calls task.pause())
- What: nothing in the transfer loop ever reads `isPaused()` (`TransferTask.kt:234` has zero callers in the copy loop). Pausing updates state to PAUSED and the UI reflects it, but the stream copy continues to completion.
- Failure scenario: user on metered data pauses a 1 GB download → progress row shows PAUSED, transfer continues and consumes the full 1 GB; then state machine gets a `complete()` on a "paused" task.
- Fix: in `transferWithProgress`'s loop, when `task.isPaused()`, loop on a short delay (suspend) until resumed or cancelled; or drop pause from the UI entirely if not supported.

### [MAJOR] [CONFIRMED] Transfer settings API is dead code — resume/permissions/timestamps/buffer/concurrency setters have no callers
- Where: `app/src/main/java/io/github/tabssh/sftp/SFTPManager.kt:51,1142` (and the sibling setters); grep across `app/src/main` finds no caller of `setResumeSupport`, `setPreservePermissions`, `setPreserveTimestamps`, `setBufferSize`, or `setMaxConcurrentTransfers` outside SFTPManager itself
- What: the settings surface exists but nothing (no settings screen, no config load) ever invokes it, so every install runs hardcoded defaults — notably `resumeSupport = true`, which is what arms the two resume-corruption BLOCKERs, and `maxConcurrentTransfers` which additionally is never even consulted by the transfer launch path (no semaphore/queue — every transfer starts immediately).
- Failure scenario: user has no way to disable the corrupting resume behavior; queueing 20 downloads opens 20 concurrent dedicated channels regardless of the "3" limit, degrading all of them on slow links.
- Fix: either wire these to the settings UI (this codebase has had "setting saved but never applied" bugs before — same class) and enforce `maxConcurrentTransfers` with a `Semaphore` in `transferScope` launches, or delete the setters and the pretense of configurability.

### [MAJOR] [CONFIRMED] SCP directory upload mangles folders whose remote target does not yet exist
- Where: `app/src/main/java/io/github/tabssh/sftp/SCPClient.kt:192-206` (no top-level `D` record, target is the new directory itself), caller `app/src/main/java/io/github/tabssh/ui/activities/SFTPActivity.kt:1018-1022` (no remote mkdir beforehand)
- What: the client runs `scp -t -r '<remoteDir>'` and immediately streams the directory's CHILDREN, never sending a `D` record for the top directory. OpenSSH's sink writes a `C` record to the target path itself when the target is not an existing directory. Since `remoteDir = "$currentRemotePath/${entry.name}"` generally does not exist yet, every top-level file record writes to the same path `<remoteDir>`, each overwriting the last; subdirectory `D` records then fail or mkdir over it.
- Failure scenario: SCP-upload a folder `photos/` containing `a.jpg`, `b.jpg` to `/home/user`. Server ends up with a single FILE `/home/user/photos` containing `b.jpg`'s bytes; toast reports success.
- Fix: match real scp clients — target the PARENT (`scp -t -r '<parent>'`) and send `D<mode> 0 <dirname>` … contents … `E` for the uploaded directory itself (i.e. wrap `uploadDirectoryContents` in a top-level D/E pair).

### [MAJOR] [CONFIRMED] Rotating the in-app editor discards unsaved edits without warning
- Where: `app/src/main/AndroidManifest.xml:414` (RemoteFileEditorActivity has no `configChanges`; only TabTerminalActivity at line 145 does), `app/src/main/java/io/github/tabssh/ui/activities/RemoteFileEditorActivity.kt` (EditText built programmatically with no view ID; onCreate re-downloads the file)
- What: on rotation the activity is recreated; a view with no ID is excluded from the instance-state save, and onCreate unconditionally re-downloads the remote file into a fresh editor. There is no onSaveInstanceState handling for the buffer.
- Failure scenario: user edits a config file for ten minutes, rotates the phone (or a foldable posture change triggers a config change) → editor reloads the server copy; all unsaved edits are gone silently.
- Fix: give the EditText a stable ID and/or persist the buffer (and dirty flag) in onSaveInstanceState, restoring it instead of re-downloading when present.

### [MAJOR] [CONFIRMED] Symlinks to directories cannot be navigated and are mis-handled as downloadable files
- Where: `app/src/main/java/io/github/tabssh/ui/activities/SFTPActivity.kt:870` (handleRemoteFileClick navigates only when `file.isDirectory`), attrs come from `ls` lstat-style entries so a symlink-to-dir has `isDirectory = false`, `isSymlink = true`
- What: tapping a symlinked directory (e.g. `/var/run → /run`, `current → releases/42`) does not navigate; selection/download paths treat it as a file of size ~0.
- Failure scenario: user browses a deploy layout where `current` is a symlink to the release dir — tapping it does nothing (or attempts a file download of the link), making common server layouts unbrowsable.
- Fix: on click of a symlink, `stat` the target (JSch `stat` follows links, unlike `lstat`) and navigate if the target is a directory, else fall through to file handling.

### [MINOR] [CONFIRMED] cleanup() cancels transferScope immediately after launching disconnect into it
- Where: `app/src/main/java/io/github/tabssh/sftp/SFTPManager.kt:1178`
- What: `cleanup()` launches channel/connection teardown into `transferScope` and then cancels that same scope; the teardown coroutine can be cancelled before it runs, leaking the shared SFTP channel until the SSH session dies.
- Failure scenario: closing an SFTP tab repeatedly leaves orphaned sftp channels on a long-lived shared SSH session (channel exhaustion on servers with MaxSessions limits).
- Fix: perform teardown synchronously (runBlocking on IO with a timeout) or use a NonCancellable block before cancelling the scope.

### [MINOR] [CONFIRMED] `listeners` list is mutated on the main thread and iterated from IO transfer threads without synchronization
- Where: `app/src/main/java/io/github/tabssh/sftp/SFTPManager.kt:56`
- What: plain `mutableListOf<SFTPListener>()` — add/remove from UI, notify from `transferScope` (Dispatchers.IO) → ConcurrentModificationException or missed notifications under concurrent transfers.
- Failure scenario: two transfers finishing while the activity registers a listener → CME crash (rare, timing dependent).
- Fix: `CopyOnWriteArrayList`.

### [MINOR] [CONFIRMED] Cancelled transfers notify listeners twice
- Where: `app/src/main/java/io/github/tabssh/sftp/TransferTask.kt:209` (`cancel()` sets the Cancelled result and notifies) plus the transfer loop's subsequent `complete(TransferResult.Cancelled)`
- What: `onTransferComplete`-style callbacks fire twice for one cancellation; UI counters/toasts double-fire.
- Fix: make `complete()` idempotent (ignore if a terminal result is already set), and have `cancel()` only set the flag.

### [MINOR] [CONFIRMED] Editor cache file `edit_<timestamp>_<name>` is never deleted
- Where: `app/src/main/java/io/github/tabssh/ui/activities/RemoteFileEditorActivity.kt:214`
- What: a new timestamped cache file per editor open, never removed in onDestroy or after save; unbounded cache growth (unlike RemoteFileOpener's LRU-evicted `file-links/`).
- Fix: delete in onDestroy (or reuse the `file-links` cache + eviction).

### [MINOR] [CONFIRMED] cancelTransfer only searches the ACTIVE tab's manager
- Where: `app/src/main/java/io/github/tabssh/ui/activities/SFTPActivity.kt:1650`
- What: the transfers sheet can show tasks from all tabs, but cancel resolves the task in the current tab's `sftpManager` only; cancelling a transfer started on another tab is a silent no-op.
- Fix: look the task up across all `SftpTab` managers by id.

### [MINOR] [PLAUSIBLE] Stale selection survives a list refresh in FileAdapter
- Where: `app/src/main/java/io/github/tabssh/ui/adapters/FileAdapter.kt:42,48,56-65,129-192`
- What: `setLocalFiles`/`setRemoteFiles` replace the list but never intersect `selectedLocalFiles`/`selectedRemoteFiles` with the new list. `RemoteFileInfo` selection equality is by full data-class value, so a refreshed entry with a changed size/mtime no longer renders selected, yet `getSelectedRemoteFiles()` still returns the stale object (old size/path state) — and entries deleted server-side remain "selected".
- Failure scenario: select files, another client deletes one, pull-to-refresh, tap Download → the batch still includes the deleted file and fails (or, with the stale size, mis-drives size-dependent logic).
- Fix: in both setters, retain only selections whose id/name is present in the incoming list (and rebind by identity, not full value equality).

## Verified-safe (checked, no finding)
- `FileOpenPolicy.cacheFileName` — path-traversal safe (hash prefix + `[^A-Za-z0-9._-]` sanitize).
- `SFTPManager.isSafeRemoteName` applied at listing/recursive-download boundaries; rejects `/`, `..`, control bytes.
- `SCPClient` single-file path: filename control-byte/`/`/`..` injection defense, correct single-quote shell escaping, streams closed in finally, ack semantics correct.
- FileProvider: `file_provider_paths.xml` covers `cache-path file-links/` — RemoteFileOpener grants resolve correctly.
- `LocalFileSource.Saf.listChildren` — single batched DocumentsContract query, tree-URI children (retains create/list capability), cursor closed via `use`.
- `TransferTask` byte accounting is Long throughout; no Int overflow.
