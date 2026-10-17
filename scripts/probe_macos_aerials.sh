#!/usr/bin/env bash
# Metadata-only probe of Apple's Aerials feeds (tvOS 26, macOS 26): manifest schema and
# stream structure of one video. Nothing is stored or committed.
#
# sylvan.apple.com chains to "Apple Root CA", which is not in the Mozilla/Ubuntu store.
# Fetch Apple's published root, check it against Apple's documented SHA-256
# fingerprint, verify the server chain with it, and use it with full verification.
set -u
work=$(mktemp -d); cd "$work"
EXPECTED="B0:B1:73:0E:CB:C7:FF:45:05:14:2C:49:F1:29:5E:6E:DA:6B:CA:ED:7E:2C:68:C5:BE:91:B5:A1:10:01:F0:24"
curl -sfS https://www.apple.com/appleca/AppleIncRootCertificate.cer -o root.der || exit 1
openssl x509 -inform DER -in root.der -out apple_root.pem
fp=$(openssl x509 -in apple_root.pem -noout -fingerprint -sha256 | cut -d= -f2)
echo "Apple Root CA sha256: $fp"
[ "$fp" = "$EXPECTED" ] || { echo "fingerprint mismatch"; exit 1; }
cat /etc/ssl/certs/ca-certificates.crt apple_root.pem > bundle.pem
curl -sS --cacert bundle.pem -o /dev/null -w "sylvan strict TLS with Apple root: %{http_code}\n" https://sylvan.apple.com/Aerials/resources-16.tar -r 0-0

probe_tar() { # $1 = label, $2 = url
  echo "=== $1: $2"
  curl -sf --cacert bundle.pem "$2" -o res.tar || { echo "fetch failed"; return; }
  tar -tf res.tar | grep -v "lproj/" | head -8
  tar -tf res.tar | grep -iE "ru\.lproj/|loctable" | head -4
  tar -xf res.tar entries.json
  python3 - "$1" <<'PY'
import json, collections, sys
d = json.load(open('entries.json'))
a = d['assets']
print(sys.argv[1], 'assets', len(a), 'top', list(d.keys()))
print('keys', dict(collections.Counter(k for x in a for k in x)))
cats = {c['id']: c.get('localizedNameKey') for c in d.get('categories', [])}
for c in d.get('categories', []):
    print('CAT', c.get('localizedNameKey'), [s.get('localizedNameKey') for s in c.get('subcategories', [])][:12])
print(json.dumps(a[-1], indent=1)[:1500])
for x in a:
    urls = {k: v for k, v in x.items() if k.startswith('url-')}
    best = urls.get('url-4K-SDR-240FPS') or urls.get('url-4K-SDR-120FPS') or urls.get('url-4K-SDR') or ''
    print('A|', x.get('accessibilityLabel'), '|', x.get('localizedNameKey'), '|', x.get('shotID'), '|',
          [cats.get(c) for c in x.get('categories', [])], '|', sorted(urls), '|', best.rsplit('/', 1)[-1])
with open('/tmp/sample_' + sys.argv[1], 'w') as f:
    pick = next((x for x in a if 'tahoe' in json.dumps(x).lower()), a[-1])
    f.write(pick.get('url-4K-SDR-240FPS') or pick.get('url-4K-SDR-120FPS') or pick.get('url-4K-SDR') or '')
PY
  sample=$(cat /tmp/sample_$1)
  echo "sample: $sample"
  ffprobe -v error -ca_file "$work/bundle.pem" -tls_verify 1 -select_streams v:0 \
    -show_entries stream=codec_name,profile,width,height,r_frame_rate,avg_frame_rate,pix_fmt,nb_frames,bit_rate:format=duration,size,bit_rate \
    "$sample"
  echo "-- HEVC nal_unit_type / temporal_id over the first 480 frames"
  ffmpeg -v debug -ca_file "$work/bundle.pem" -tls_verify 1 -i "$sample" -map 0:v:0 -c copy -bsf:v trace_headers \
    -frames:v 480 -f null - > trace.txt 2>&1 || true
  grep -m 6 -E "nal_unit_type|temporal_id" trace.txt || echo "(no trace lines)"
  grep -E "nal_unit_type|nuh_temporal_id_plus1" trace.txt | awk '{print $(NF-2)"="$NF}' \
    | paste -d' ' - - | sort | uniq -c | sort -rn | head -16 || true
}

probe_tar tvos26 https://sylvan.apple.com/itunes-assets/Aerials126/v4/c0/45/d9/c045d9d0-9606-1535-62fe-189edb4f79eb/resources-atv-23J-2.tar
cfg=$(curl -sf https://configuration.apple.com/configurations/internetservices/aerials/resources-config-26-0.plist \
  | python3 -c "import plistlib,sys;print(plistlib.loads(sys.stdin.buffer.read())['resources-url'])")
probe_tar macos26 "$cfg"
