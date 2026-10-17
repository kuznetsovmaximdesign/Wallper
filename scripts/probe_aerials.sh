#!/usr/bin/env bash
# Probes Apple's public Aerials CDN: lists manifests and prints the asset schema.
set -uo pipefail
work=$(mktemp -d)
cd "$work"
getent hosts sylvan.apple.com || echo "DNS failed"
curl -sSv -o /dev/null https://sylvan.apple.com/Aerials/resources-16.tar 2>&1 | grep -E "^\* |subject|issuer|HTTP|curl:" | head -30
echo "--- insecure / plain http"
for v in 13 14 15 16 17 18 19; do
  for scheme in https http; do
    code=$(curl -sk -o /dev/null -w "%{http_code} %{size_download}" "$scheme://sylvan.apple.com/Aerials/resources-$v.tar")
    echo "manifest v$v $scheme -> $code"
  done
done
for v in 16 15 17 18; do
  url="https://sylvan.apple.com/Aerials/resources-$v.tar"
  if curl -sfLk -o res.tar "$url" || curl -sfL -o res.tar "${url/https/http}"; then
    echo "== using $url ($(stat -c %s res.tar) bytes)"
    tar -tf res.tar | head -40
    tar -xf res.tar entries.json 2>/dev/null || tar -xf res.tar
    python3 - <<'PY'
import json, collections
d = json.load(open('entries.json'))
print('top keys:', list(d.keys()))
assets = d.get('assets', [])
print('assets:', len(assets))
keys = collections.Counter(k for a in assets for k in a)
print('asset keys:', dict(keys))
print('sample:', json.dumps(assets[0], indent=1)[:2500])
for c in d.get('categories', [])[:20]:
    print('category:', c.get('localizedNameKey'), c.get('id'), [s.get('localizedNameKey') for s in c.get('subcategories', [])])
for a in assets:
    u = a.get('url-4K-SDR-240FPS') or a.get('url-4K-SDR') or ''
    print('ASSET', a.get('id'), '|', a.get('accessibilityLabel'), '|', a.get('localizedNameKey'), '|', u, '|', a.get('previewImage', ''))
PY
    break
  fi
done
# TLS + size check of the first 4K url
first=$(python3 -c "import json;a=json.load(open('entries.json'))['assets'];print(next(x.get('url-4K-SDR-240FPS') or x.get('url-4K-SDR') for x in a if x.get('url-4K-SDR-240FPS') or x.get('url-4K-SDR')))")
echo "first 4K url: $first"
curl -sSI "$first" 2>&1 | head -20 || echo "TLS/HEAD failed"
curl -sI -k "$first" | grep -i -E "HTTP|content-length|content-type" || true
ffprobe -v error -show_entries stream=codec_name,width,height,r_frame_rate,pix_fmt:format=duration,format_name "$first" || \
  ffprobe -v error -tls_verify 0 -show_entries stream=codec_name,width,height,r_frame_rate:format=duration "$first" || true
