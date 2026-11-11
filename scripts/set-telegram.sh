#!/bin/sh
# One-time setup; see docs/WORKFLOW.md §5.
# Sets up the Telegram bot on the phone without the token ever being printed,
# saved on the Mac, or committed. Usage: scripts/set-telegram.sh
set -e

printf 'Paste the bot token from BotFather (input hidden), then Enter: '
stty -echo; read -r TOKEN; stty echo; echo
[ -n "$TOKEN" ] || { echo "No token entered."; exit 1; }

# curl reads the URL (which contains the token) from stdin, so it never appears in `ps`.
api() {
  printf 'url = "https://api.telegram.org/bot%s/%s"\n' "$TOKEN" "$1" \
    | curl -sS --max-time 30 -K - 2>/dev/null || echo '{"ok": false}'
}

ME=$(api getMe)
echo "$ME" | python3 -I -c 'import json,sys; d=json.load(sys.stdin); sys.exit(0 if d.get("ok") else 1)' \
  || { echo "Telegram rejected that token. Copy it again from BotFather."; exit 1; }
BOT=$(echo "$ME" | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["result"]["username"])')
echo "Token OK: bot is @$BOT"

echo "Now open Telegram, find @$BOT, and send it: /start"
echo "Waiting for your message..."
USER_ID=""
for i in $(seq 1 60); do
  USER_ID=$(api "getUpdates?timeout=5" | python3 -I -c '
import json, sys
d = json.load(sys.stdin)
msgs = [u["message"] for u in d.get("result", []) if "message" in u]
print(msgs[-1]["from"]["id"] if msgs else "")
')
  [ -n "$USER_ID" ] && break
done
[ -n "$USER_ID" ] || { echo "No message arrived in 5 minutes. Run the script again."; exit 1; }
echo "Your Telegram user ID: $USER_ID"

# Write config on the phone via stdin, then open the app so it imports it right away
# (the app moves it to private storage and deletes the /sdcard copy).
printf '{"telegramBotToken": "%s", "telegramUserId": %s}\n' "$TOKEN" "$USER_ID" \
  | ssh phone 'cat > /sdcard/cctv/config.json && am start -n com.nandan.cctv/.MainActivity >/dev/null'
TOKEN=""
sleep 5
ssh phone 'ls /sdcard/cctv/config.json 2>/dev/null && echo "WARNING: config.json still on /sdcard" || echo "Config imported into the app."; grep "telegram" /sdcard/cctv/log.txt | tail -2'
echo "You should get a message from @$BOT within a minute."
