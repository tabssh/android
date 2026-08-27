# TODO — TabSSH Android

Task tracking (AI-owned). Items are ordered by priority, highest first.
Complete each item fully before removing; never clear an item while its work
is in progress.

## UI/UX issues found during SSH error-dialog research (not yet fixed)

Found while researching the SSH error classifier fix (see chat/PR for that
work).

51. **`make check`'s Gradle task list never runs resource linking, so a
    broken `AndroidManifest.xml` `android:string` reference is invisible to
    the local pre-commit gate** — `check`'s invocation
    (`kspDebugKotlin compileDebugKotlin lintDebug testDebugUnitTest`) has no
    `assembleDebug`/`assembleDevel`/AAPT step. Discovered when the item-49
    duplicate-string-consolidation commit (`8bd3b158712f`) deleted 4 string
    keys still referenced by `AndroidManifest.xml` `android:label`
    attributes — `make check` passed locally, but CI's
    `development.yml` (`./gradlew assembleDevel --no-daemon`) failed with
    AAPT "resource ... not found" errors; fixed in commit `f415d8d22ad4`.
    Fix: add a resource-linking-capable task (e.g. `assembleDebug` or a
    lighter `processDebugResources`-only invocation, whichever is faster) to
    the `check` target's Gradle task list so this class of bug is caught
    before commit, not after push.

