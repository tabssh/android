#!/usr/bin/env bash
# Publish or delete a provider release over REST (Gitea, Forgejo, GitLab).
#
# The toolchain image ships only gh, curl and jq (AI.md PART 12), so releases
# on Gitea, Forgejo and GitLab go through their REST APIs instead of tea/glab.
#
# Usage:
#   provider-release.sh delete <tag>
#   provider-release.sh create <tag> <title> <notes-file> <prerelease> <target-sha> <file>...
#
# Environment:
#   PROVIDER   gitea | forgejo | gitlab
#   API_URL    API base URL, e.g. https://git.example.com/api/v1 or $CI_API_V4_URL
#   REPO       owner/name (gitea, forgejo) or the numeric project id (gitlab)
#   TOKEN      API token with release write access (CI_JOB_TOKEN works on gitlab)
#   PACKAGE    gitlab only: generic package name for the uploaded assets
set -euo pipefail

usage() {
  echo "usage: $0 delete <tag> | create <tag> <title> <notes-file> <prerelease> <target-sha> <file>..." >&2
  exit 2
}

for var in PROVIDER API_URL REPO TOKEN; do
  if [ -z "${!var:-}" ]; then
    echo "::error::$var is not set. Set it in the workflow env (the token is a repository secret)." >&2
    exit 1
  fi
done

action="${1:-}"
[ -n "$action" ] || usage
shift

case "$PROVIDER" in
  gitea | forgejo) auth=(-H "Authorization: token $TOKEN") ;;
  gitlab)
    if [ "$TOKEN" = "${CI_JOB_TOKEN:-}" ]; then
      auth=(-H "JOB-TOKEN: $TOKEN")
    else
      auth=(-H "PRIVATE-TOKEN: $TOKEN")
    fi
    ;;
  *)
    echo "::error::Unsupported PROVIDER '$PROVIDER' (use gitea, forgejo or gitlab)." >&2
    exit 1
    ;;
esac

delete_release() {
  local tag="$1" rid
  case "$PROVIDER" in
    gitea | forgejo)
      rid="$(curl -sS "${auth[@]}" "$API_URL/repos/$REPO/releases/tags/$tag" | jq -r '.id // empty')" || rid=""
      if [ -n "$rid" ]; then
        curl -fsS -X DELETE "${auth[@]}" "$API_URL/repos/$REPO/releases/$rid"
      fi
      curl -sS -o /dev/null -X DELETE "${auth[@]}" "$API_URL/repos/$REPO/tags/$tag" || true
      ;;
    gitlab)
      curl -sS -o /dev/null -X DELETE "${auth[@]}" "$API_URL/projects/$REPO/releases/$tag" || true
      curl -sS -o /dev/null -X DELETE "${auth[@]}" "$API_URL/projects/$REPO/repository/tags/$tag" || true
      ;;
  esac
}

create_release() {
  local tag="$1" title="$2" notes_file="$3" prerelease="$4" target="$5" body rid file links='[]'
  shift 5
  [ "$#" -gt 0 ] || { echo "::error::No release assets given." >&2; exit 1; }
  case "$PROVIDER" in
    gitea | forgejo)
      body="$(jq -n --arg t "$tag" --arg n "$title" --arg b "$(cat "$notes_file")" --arg c "$target" \
        --argjson p "$prerelease" \
        '{tag_name: $t, name: $n, body: $b, target_commitish: $c, prerelease: $p, draft: false}')"
      rid="$(curl -fsS -X POST "${auth[@]}" -H 'Content-Type: application/json' -d "$body" \
        "$API_URL/repos/$REPO/releases" | jq -r '.id')"
      for file in "$@"; do
        curl -fsS -X POST "${auth[@]}" -F "attachment=@$file" \
          "$API_URL/repos/$REPO/releases/$rid/assets?name=$(basename "$file")" > /dev/null
      done
      ;;
    gitlab)
      for file in "$@"; do
        url="$API_URL/projects/$REPO/packages/generic/${PACKAGE:-release}/$tag/$(basename "$file")"
        curl -fsS "${auth[@]}" --upload-file "$file" "$url" > /dev/null
        links="$(jq -c --arg n "$(basename "$file")" --arg u "$url" '. + [{name: $n, url: $u, link_type: "package"}]' <<< "$links")"
      done
      body="$(jq -n --arg t "$tag" --arg n "$title" --arg d "$(cat "$notes_file")" --arg r "$target" \
        --argjson l "$links" \
        '{tag_name: $t, name: $n, description: $d, ref: $r, assets: {links: $l}}')"
      curl -fsS -X POST "${auth[@]}" -H 'Content-Type: application/json' -d "$body" \
        "$API_URL/projects/$REPO/releases" > /dev/null
      ;;
  esac
  echo "Release $tag created ($title)"
}

case "$action" in
  delete)
    [ "$#" -eq 1 ] || usage
    delete_release "$1"
    ;;
  create)
    [ "$#" -ge 6 ] || usage
    create_release "$@"
    ;;
  *) usage ;;
esac
