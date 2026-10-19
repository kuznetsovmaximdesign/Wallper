#!/usr/bin/env bash
# Metadata-only probe of Apple's Aerials feeds: categories, subcategories (with Apple's
# Russian names) and every scene's labels, to design finer categories. Nothing is stored
# or committed; only text goes to the log.
set -uo pipefail
work=$(mktemp -d); cd "$work"
EXPECTED="B0:B1:73:0E:CB:C7:FF:45:05:14:2C:49:F1:29:5E:6E:DA:6B:CA:ED:7E:2C:68:C5:BE:91:B5:A1:10:01:F0:24"
curl -sfS https://www.apple.com/appleca/AppleIncRootCertificate.cer -o root.der || exit 1
openssl x509 -inform DER -in root.der -out apple_root.pem
fp=$(openssl x509 -in apple_root.pem -noout -fingerprint -sha256 | cut -d= -f2)
[ "$fp" = "$EXPECTED" ] || { echo "fingerprint mismatch"; exit 1; }
cat /etc/ssl/certs/ca-certificates.crt apple_root.pem > bundle.pem

probe() { # $1 = label, $2 = url
  echo "=== $1"
  curl -sf --cacert bundle.pem "$2" -o res.tar || { echo "fetch failed"; return; }
  mkdir -p "$1" && tar -xf res.tar -C "$1" entries.json $(tar -tf res.tar | grep "ru.lproj/Localizable.nocache.strings" | head -1) 2>/dev/null
  python3 - "$1" <<'PY'
import json, sys, collections, glob, plistlib, re
d = json.load(open(sys.argv[1] + '/entries.json'))
ru = {}
for f in glob.glob(sys.argv[1] + '/**/ru.lproj/Localizable.nocache.strings', recursive=True):
    raw = open(f, 'rb').read()
    try:
        ru = plistlib.loads(raw)
    except Exception:
        text = raw.decode('utf-16') if raw[:2] in (b'\xff\xfe', b'\xfe\xff') else raw.decode('utf-8', 'ignore')
        ru = dict(re.findall(r'"([^"]+)"\s*=\s*"([^"]*)"', text))
cats = {c['id']: c for c in d.get('categories', [])}
subs = {s['id']: (c.get('localizedNameKey'), s.get('localizedNameKey')) for c in d.get('categories', []) for s in c.get('subcategories', [])}
count = collections.Counter()
for a in d['assets']:
    has4k = bool(a.get('url-4K-SDR') or a.get('url-4K-SDR-240FPS'))
    cs = [cats[c].get('localizedNameKey') for c in a.get('categories', []) if c in cats]
    ss = [subs[s][1] for s in a.get('subcategories', []) if s in subs]
    for c in cs: count[(c, has4k)] += 1
    print('S|', a.get('accessibilityLabel'), '|', ru.get(a.get('localizedNameKey', ''), ''), '|', cs, '|',
          [(s, ru.get(s, '')) for s in ss], '| 4K' if has4k else '| no4K')
for c in d.get('categories', []):
    print('CAT|', c.get('localizedNameKey'), ru.get(c.get('localizedNameKey'), ''))
    for s in c.get('subcategories', []):
        print('  SUB|', s.get('localizedNameKey'), '|', ru.get(s.get('localizedNameKey'), ''))
print('COUNTS', dict(count))
PY
}
probe tvos https://sylvan.apple.com/Aerials/resources-16.tar
cfg=$(curl -sf https://configuration.apple.com/configurations/internetservices/aerials/resources-config-26-0.plist \
  | python3 -c "import plistlib,sys;print(plistlib.loads(sys.stdin.buffer.read())['resources-url'])")
probe macos "$cfg"
