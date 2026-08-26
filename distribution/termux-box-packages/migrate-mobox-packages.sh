#!/usr/bin/env bash
set -euo pipefail

OWNER="${OWNER:-jiaxinchen-max}"
REPOSITORY="${REPOSITORY:-termux-box-packages}"
REPO="${OWNER}/${REPOSITORY}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CATALOG="${CATALOG:-${SCRIPT_DIR}/catalog-v1.json}"
WORK_DIR="${WORK_DIR:-${TMPDIR:-/tmp}/termux-box-package-migration}"
DOWNLOAD_DIR="${WORK_DIR}/downloads"
REPO_DIR="${WORK_DIR}/repository"

require_command() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "Missing command: $1" >&2
        exit 1
    }
}

for command in curl git gh jq sha256sum tar; do
    require_command "$command"
done

gh auth status -h github.com >/dev/null
mkdir -p "$DOWNLOAD_DIR"

if ! gh repo view "$REPO" >/dev/null 2>&1; then
    gh repo create "$REPO" --public --description "Versioned runtime packages for Termux Box"
fi

rm -rf "$REPO_DIR"
git clone "https://github.com/${REPO}.git" "$REPO_DIR"

if [ ! -f "$REPO_DIR/README.md" ]; then
    cp "$SCRIPT_DIR/README.md" "$REPO_DIR/README.md"
    git -C "$REPO_DIR" add README.md
    git -C "$REPO_DIR" commit -m "Initialize package repository"
    git -C "$REPO_DIR" push origin HEAD:main
fi

while IFS=$'\t' read -r id version source_url expected_size expected_sha; do
    archive="${DOWNLOAD_DIR}/${id}.tar.xz"
    tag="${id}-v${version}"

    if [ ! -f "$archive" ]; then
        curl --fail --location --retry 5 --retry-delay 2 \
            --output "${archive}.part" "$source_url"
        mv "${archive}.part" "$archive"
    fi

    actual_size="$(wc -c < "$archive" | tr -d ' ')"
    actual_sha="$(sha256sum "$archive" | awk '{print $1}')"
    [ "$actual_size" = "$expected_size" ] || {
        echo "Size mismatch: $id" >&2
        exit 1
    }
    [ "$actual_sha" = "$expected_sha" ] || {
        echo "SHA-256 mismatch: $id" >&2
        exit 1
    }
    tar -tf "$archive" | awk '
        $0 !~ /^glibc(\/|$)/ { invalid = 1 }
        END { exit invalid }
    ' || {
        echo "Archive contains paths outside glibc/: $id" >&2
        exit 1
    }

    if gh release view "$tag" --repo "$REPO" >/dev/null 2>&1; then
        verify_dir="${WORK_DIR}/verify-${id}"
        rm -rf "$verify_dir"
        mkdir -p "$verify_dir"
        gh release download "$tag" --repo "$REPO" \
            --pattern "${id}.tar.xz" --dir "$verify_dir"
        published_sha="$(sha256sum "${verify_dir}/${id}.tar.xz" | awk '{print $1}')"
        [ "$published_sha" = "$expected_sha" ] || {
            echo "Published release differs: $tag" >&2
            exit 1
        }
    else
        gh release create "$tag" "$archive" --repo "$REPO" --target main \
            --title "${id} v${version}" --notes "SHA-256: ${expected_sha}"
    fi
done < <(jq -r '.packages[] | [.id, (.version|tostring), .url, (.size|tostring), .sha256] | @tsv' "$CATALOG")

mkdir -p "$REPO_DIR/repository"
jq --arg owner "$OWNER" --arg repository "$REPOSITORY" '
    .generatedAt = (now | todateiso8601)
    | .packages |= map(
        .url = ("https://github.com/" + $owner + "/" + $repository
            + "/releases/download/" + .id + "-v" + (.version|tostring)
            + "/" + .id + ".tar.xz")
    )
' "$CATALOG" > "$REPO_DIR/repository/index-v1.json"

git -C "$REPO_DIR" add README.md repository/index-v1.json
if ! git -C "$REPO_DIR" diff --cached --quiet; then
    git -C "$REPO_DIR" commit -m "Publish package index"
    git -C "$REPO_DIR" push origin HEAD:main
fi

echo "Published https://github.com/${REPO}"
