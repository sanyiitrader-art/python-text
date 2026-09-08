#!/usr/bin/env bash
# Generates Android launcher icon resources from icon.png at the repo root.
# Run by the GitHub Actions workflow before Gradle builds (spec §13).
# Requires ImageMagick's `convert`, which is preinstalled on ubuntu-latest
# GitHub runners.

set -euo pipefail

SRC="icon.png"
RES_DIR="app/src/main/res"

if [ ! -f "$SRC" ]; then
    echo "ERROR: $SRC not found at repository root." >&2
    exit 1
fi

# --- Legacy launcher icon (square, all density buckets) -------------------
declare -A SIZES=(
    [mdpi]=48
    [hdpi]=72
    [xhdpi]=96
    [xxhdpi]=144
    [xxxhdpi]=192
)

for density in "${!SIZES[@]}"; do
    size="${SIZES[$density]}"
    dir="$RES_DIR/mipmap-$density"
    mkdir -p "$dir"
    convert "$SRC" -resize "${size}x${size}" "$dir/ic_launcher.png"
    convert "$SRC" -resize "${size}x${size}" \
        \( -size "${size}x${size}" xc:none -fill white -draw "circle $((size/2)),$((size/2)) $((size/2)),0" \) \
        -compose SrcIn -composite "$dir/ic_launcher_round.png"
done

# --- Adaptive icon (API 26+) -----------------------------------------------
# Foreground layer needs extra padding (adaptive icons are drawn inside a
# larger canvas than they display, so content must be inset to avoid being
# cropped by the launcher's mask).
FG_DIR="$RES_DIR/mipmap-xxxhdpi"
mkdir -p "$FG_DIR"
convert "$SRC" -resize 132x132 -gravity center -background none -extent 192x192 \
    "$FG_DIR/ic_launcher_foreground.png"

mkdir -p "$RES_DIR/mipmap-anydpi-v26"
cat > "$RES_DIR/mipmap-anydpi-v26/ic_launcher.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/editor_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
EOF

cp "$RES_DIR/mipmap-anydpi-v26/ic_launcher.xml" "$RES_DIR/mipmap-anydpi-v26/ic_launcher_round.xml"

echo "Icon generation complete."