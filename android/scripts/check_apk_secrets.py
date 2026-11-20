#!/usr/bin/env python3
# Checks a built APK for secrets before it is handed out. Python 3 standard library only; reads the zip in memory, writes nothing.
# Exit 1 if any file in the apk holds a string shaped like a secret (API keys, device tokens, private keys, bearer tokens).
# An APERIT Pro enrollment ticket (apt_...) is expected in a ticket build: it is reported, never fails the check, and never printed.
# Every http(s) URL host found is listed so a reviewer can see which servers the app talks to.
import collections
import re
import sys
import zipfile

FAIL = {
    "anthropic key (sk-ant-)": rb"sk-ant-[A-Za-z0-9_\-]{20,}",
    "generic key (sk-)": rb"(?<![A-Za-z0-9_])sk-[A-Za-z0-9_\-]{30,}",
    "elevenlabs-style key (sk_)": rb"(?<![A-Za-z0-9_])sk_[A-Za-z0-9]{40,}",
    "aperit device token (apd_)": rb"apd_[A-Za-z0-9_\-]{20,}",
    # a header followed by key material (libraries such as JSch carry the bare header text as a constant)
    "private key block": rb"-----BEGIN [A-Z ]*PRIVATE KEY-----(?:\\r?\\n|\r?\n)(?:[A-Za-z0-9+/=]{16,}\r?\n?){2,}",
    "bearer token": rb"Bearer [A-Za-z0-9_\-\.=]{20,}",
    "aws access key": rb"(?<![A-Z0-9])AKIA[0-9A-Z]{16}(?![A-Z0-9])",
    "google api key": rb"AIza[0-9A-Za-z_\-]{35}",
    "github token": rb"gh[pousr]_[A-Za-z0-9]{36,}",
    "slack token": rb"xox[abposr]-[A-Za-z0-9\-]{10,}",
}
EXPECTED = {"aperit enrollment ticket (apt_)": rb"apt_[A-Za-z0-9_\-]{16,}"}
URL = re.compile(rb"https?://([A-Za-z0-9\.\-]+(?::[0-9]+)?)")


def views(data):
    yield data
    if b"\x00" in data:
        yield data.replace(b"\x00", b"")  # UTF-16 strings (resources.arsc, binary XML)


def main(path):
    z = zipfile.ZipFile(path)
    bad = collections.Counter()
    where = collections.defaultdict(set)
    seen = collections.Counter()
    hosts = collections.Counter()
    for info in z.infolist():
        data = z.read(info)
        for v in views(data):
            for name, pat in FAIL.items():
                n = len(re.findall(pat, v))
                if n:
                    bad[name] += n
                    where[name].add(info.filename)
            for name, pat in EXPECTED.items():
                seen[name] += len(re.findall(pat, v))
            if v is data:
                for m in URL.finditer(v):
                    hosts[m.group(1).decode("ascii", "replace").lower()] += 1
    print("apk:", path)
    for name in FAIL:
        print("  %-30s %d%s" % (name, bad[name], ("  in " + ", ".join(sorted(where[name]))) if bad[name] else ""))
    for name in EXPECTED:
        print("  %-30s %d%s" % (name, seen[name], "  (expected in a ticket build, not printed)" if seen[name] else ""))
    print("url hosts (%d):" % len(hosts))
    for h, n in sorted(hosts.items()):
        print("  %s  x%d" % (h, n))
    if bad:
        print("FAIL: secret-shaped strings found")
        return 1
    print("PASS")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: check_apk_secrets.py <apk>")
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
