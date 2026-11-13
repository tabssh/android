# TODO.AI.md

- Unused strings: 128 `res/values/strings*.xml` names have zero references in code/XML (e.g. `app_name_debug`, `error_host_key_changed`, `pref_auto_lock_summary`). Deferred to the UI/strings pass because each also exists in values-de/es/fr; re-verify against `getIdentifier` use before deleting.
- `TerminalView.beginWordSelectionAtTouch` has no callers after removal of the dead `TabTerminalActivity.beginSelection`; delete it (and its KDoc) if no reflection/test use.
- Tracker activities (`VpsTrackerActivity`, `DomainTrackerActivity`) call `adapter.notifyDataSetChanged()` for selection refresh on a ListAdapter; switching to `notifyItemRangeChanged` may cause change-animation flicker, so left as is pending UI check.
- `FileAdapter` (plain RecyclerView.Adapter) uses `notifyDataSetChanged()` at two sites; migrate to `replaceAllWithDiff` once item identity is confirmed.
- `OciApiClient` ctor params `pinnedCertSha256` / `onPinCaptured` are `@Suppress("unused")`; removing them needs call-site updates in `OciCloudClient` and `OciManagerActivity`.
