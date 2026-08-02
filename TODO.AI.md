# TODO

- Non-http URL schemes are detected but mishandled on tap (TerminalView urlPattern covers http/https/ftp/ftps/ssh/git/svn/file; TabTerminalActivity.showUrlDialog/openUrl treats all as browser links via ACTION_VIEW):
  - `file://` can never open — resolves against the local device, and API 24+ throws FileUriExposedException; should drop "Open" and offer "Copy path" / "Open in SFTP" (path is on the remote host)
  - `ssh://` should be handled in-app (parse user/host/port → new connection tab); no manifest intent-filter for the ssh scheme exists either
  - `git://` / `ftp://` / `ftps://` / `svn://` should show "Open" only when `resolveActivity()` finds a handler; make "Copy" primary otherwise
  - Dialog copy says "This will open in your browser" for every scheme — only true for http(s)
