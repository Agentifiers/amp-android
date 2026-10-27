#!/usr/bin/env bash
# Smoke test against a deployed relay: health, register, update, delete, gone, and one push to a placeholder
# FCM token. The push answers 500 while FCM_SERVICE_ACCOUNT is unset, and 410 once Google accepts the
# service account and rejects the placeholder token.
# Usage: relay/scripts/smoke.sh https://amp-push-relay.<subdomain>.workers.dev
set -euo pipefail
base="${1:?relay origin}"
fail() { echo "FAIL: $*" >&2; exit 1; }
status() { curl -s -o /tmp/relay-smoke.body -w '%{http_code}' "$@"; }
register() {
	[ "$(status -X POST "$base/v1/registrations" -H 'Content-Type: application/json' -d '{"fcmToken":"smoke-test-token"}')" = 201 ] || fail "register $(cat /tmp/relay-smoke.body)"
	rid=$(jq -r .rid /tmp/relay-smoke.body)
	secret=$(jq -r .secret /tmp/relay-smoke.body)
	push_url=$(jq -r .pushUrl /tmp/relay-smoke.body)
	case "$push_url" in "$base/v1/push/"*) ;; *) fail "push URL host";; esac
}

[ "$(status "$base/health")" = 200 ] || fail "health"
echo "ok health"

register
echo "ok register rid=${rid:0:8}"
[ "$(status -X POST "$push_url" -H 'Content-Encoding: gzip' --data-binary x)" = 400 ] || fail "wrong encoding"
echo "ok push rejects other encodings"
[ "$(status -X PUT "$base/v1/registrations/$rid" -H 'Authorization: Bearer wrong' -H 'Content-Type: application/json' -d '{"fcmToken":"t2"}')" = 401 ] || fail "update with wrong secret"
[ "$(status -X PUT "$base/v1/registrations/$rid" -H "Authorization: Bearer $secret" -H 'Content-Type: application/json' -d '{"fcmToken":"smoke-test-token-2"}')" = 204 ] || fail "update"
echo "ok update"
[ "$(status -X DELETE "$base/v1/registrations/$rid" -H "Authorization: Bearer $secret")" = 204 ] || fail "delete"
[ "$(status -X POST "$push_url" -H 'Content-Encoding: aes128gcm' --data-binary x)" = 410 ] || fail "push after delete"
echo "ok delete, then push answers 410"

register
case "$(status -X POST "$push_url" -H 'Content-Encoding: aes128gcm' -H 'TTL: 60' --data-binary 'ciphertext-placeholder')" in
	410) echo "ok FCM accepted the service account and rejected the placeholder token, so the relay deleted it" ;;
	500)
		echo "ok push answered 500: FCM_SERVICE_ACCOUNT is unset or Google refused it (wrangler tail shows the reason)"
		[ "$(status -X DELETE "$base/v1/registrations/$rid" -H "Authorization: Bearer $secret")" = 204 ] || fail "cleanup delete"
		;;
	*) fail "push $(cat /tmp/relay-smoke.body)" ;;
esac
