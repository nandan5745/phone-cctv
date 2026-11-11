#!/bin/sh
# Part of the deploy chain in docs/WORKFLOW.md §3 (CI artifact → phone → installer).
# Download the newest APK built on GitHub and open the installer on the phone.
# Usage: scripts/deploy.sh          (then tap "Install" on the phone)
set -e
cd "$(dirname "$0")/.."
rm -rf .deploy && mkdir .deploy

RUN_ID=$(gh run list --workflow build.yml --status success --limit 1 --json databaseId -q '.[0].databaseId')
[ -n "$RUN_ID" ] || { echo "No successful build found"; exit 1; }
echo "Downloading APK from run $RUN_ID"
gh run download "$RUN_ID" --name cctv-apk --dir .deploy

scp .deploy/cctv.apk phone:/sdcard/Download/cctv.apk

# The installer screen only appears while the phone is unlocked.
printf 'Unlock the phone, then press Enter to open the installer... '
read _

ssh phone 'am start -a android.intent.action.VIEW -t application/vnd.android.package-archive -d file:///sdcard/Download/cctv.apk \
  || termux-open /sdcard/Download/cctv.apk'
echo "Now tap Install on the phone."
