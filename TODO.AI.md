# TODO.AI.md

- SFTP: the SCP fallback upload path (`askScpModeAndUpload`/`uploadSelectedFilesViaScp`
  in `SFTPActivity.kt`) still skips directories entirely — recursive
  directory upload was added to the primary SFTP path (`SFTPManager.uploadDirectory`)
  but not to `SCPClient`. Add recursive `-r`-style directory support to
  `SCPClient` and wire it in, mirroring the SFTP fix.
- SFTP: "Download Folder" (`showRemoteFileMenu`'s directory branch, and the
  download button's directory skip in `downloadSelectedFiles()`) has no
  recursive directory-download counterpart to the new `uploadDirectory`.
  `sftpManager.downloadFile()` assumes single-file semantics; calling it on
  a directory `RemoteFileInfo` will fail. Add a matching
  `SFTPManager.downloadDirectory()` and wire it into both call sites.
