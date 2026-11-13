# TODO.AI.md

- `FileAdapter` (plain RecyclerView.Adapter) uses `notifyDataSetChanged()` at two sites; migrate to `replaceAllWithDiff` once item identity is confirmed.
- `OciApiClient` ctor params `pinnedCertSha256` / `onPinCaptured` are `@Suppress("unused")`; removing them needs call-site updates in `OciCloudClient` and `OciManagerActivity`.
