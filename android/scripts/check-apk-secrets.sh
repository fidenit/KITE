#!/bin/sh
# usage: android/scripts/check-apk-secrets.sh <apk>   (exit 1 = a secret-shaped string is in the apk)
exec python3 -I "$(dirname "$0")/check_apk_secrets.py" "$@"
