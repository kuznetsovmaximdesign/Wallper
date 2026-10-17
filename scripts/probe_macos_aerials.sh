#!/usr/bin/env bash
# Metadata-only probe of Apple's macOS Aerials feeds: manifest schema and the stream
# structure of one video (frame rate, HEVC temporal layers). Nothing is stored.
set -uo pipefail
# The CDN omits its intermediate certificate; complete the chain via AIA (as browsers
# do) and keep full verification against the system roots.
work=$(mktemp -d); cd "$work"
leaf=$(openssl s_client -connect sylvan.apple.com:443 -servername sylvan.apple.com </dev/null 2>/dev/null | openssl x509)
aia=$(echo "$leaf" | openssl x509 -noout -ext authorityInfoAccess | sed -n 's/.*CA Issuers - URI:\(.*\)/\1/p' | head -1)
echo "AIA issuer: $aia"
curl -sf "$aia" -o issuer.der && openssl x509 -inform DER -in issuer.der -out issuer.pem
echo "$leaf" > leaf.pem
openssl verify -CAfile /etc/ssl/certs/ca-certificates.crt -untrusted issuer.pem leaf.pem || exit 1
cat /etc/ssl/certs/ca-certificates.crt issuer.pem > bundle.pem

for v in 26-0 27-0 15-0; do
  echo "=== resources-config-$v.plist"
  curl -sf --cacert bundle.pem "https://configuration.apple.com/configurations/internetservices/aerials/resources-config-$v.plist" -o cfg-$v.plist && \
    python3 -c "import plistlib,sys;print(plistlib.load(open('cfg-$v.plist','rb')))" | head -c 1500; echo
done

url=$(python3 -c "import plistlib;print(plistlib.load(open('cfg-26-0.plist','rb')).get('resources-url',''))" 2>/dev/null)
echo "resources url: $url"
curl -sf --cacert bundle.pem "$url" -o res.tar || exit 0
tar -tf res.tar | grep -v lproj/ | head -20
tar -tf res.tar | grep -i "ru.lproj\|\.loctable" | head
tar -xf res.tar entries.json
python3 - <<'PY'
import json, collections
d = json.load(open('entries.json'))
a = d['assets']
print('assets', len(a), 'top', list(d.keys()))
print('keys', dict(collections.Counter(k for x in a for k in x)))
cats = {c['id']: c.get('localizedNameKey') for c in d.get('categories', [])}
for c in d.get('categories', []):
    print('CAT', c.get('localizedNameKey'), [s.get('localizedNameKey') for s in c.get('subcategories', [])][:12])
print(json.dumps(a[0], indent=1)[:1800])
for x in a:
    u = x.get('url-4K-SDR-240FPS') or x.get('url-4K-SDR-120FPS') or x.get('url-4K-SDR') or ''
    print('A|', x.get('accessibilityLabel'), '|', x.get('localizedNameKey'), '|', [cats.get(c) for c in x.get('categories', [])], '|', u.rsplit('/', 1)[-1])
open('/tmp/sample_url', 'w').write(next((x.get('url-4K-SDR-240FPS') or x.get('url-4K-SDR-120FPS') or '') for x in a if 'Tahoe' in (x.get('accessibilityLabel') or '') or 'TH' in (x.get('shotID') or '')) or (a[0].get('url-4K-SDR-240FPS') or ''))
PY
sample=$(cat /tmp/sample_url)
echo "sample: $sample"
ffprobe -v error -ca_file bundle.pem -tls_verify 1 -select_streams v:0 \
  -show_entries stream=codec_name,profile,width,height,r_frame_rate,avg_frame_rate,pix_fmt,nb_frames:format=duration,size \
  "$sample"
# HEVC NAL types and temporal ids of the first 2 seconds of frames.
ffmpeg -v error -ca_file bundle.pem -tls_verify 1 -i "$sample" -map 0:v:0 -c copy -bsf:v trace_headers \
  -frames:v 480 -f null - 2>&1 | grep -E "nal_unit_type|nuh_temporal_id_plus1" | paste - - \
  | awk '{print $NF, $(NF-2)}' | sort | uniq -c | sort -rn | head -20
