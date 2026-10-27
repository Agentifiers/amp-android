# Amp push relay

`amp-push-relay` is a Cloudflare Worker that receives Amp's Web Push at a capability URL and forwards the encrypted body to the Android app as a Firebase Cloud Messaging (FCM) data message. The app registers its FCM token, gets back a push URL, and gives that URL to Amp. The relay never sees plaintext, because Amp encrypts each push to a key that only the app holds. The design is in [`docs/design/push-relay.md`](../docs/design/push-relay.md).

The relay is optional. An app built without `amp.pushRelayUrl` never registers with a relay. It gets push through a UnifiedPush distributor such as ntfy, or it polls.

## Contract with the app

- `rid` is the unpadded base64url of SHA-256 over the UTF-8 bytes of the capability, which is the last path segment of `pushUrl`.
- An FCM message carries `data.v = "1"`, `data.rid`, and either `data.body` (the request body as unpadded base64url) or `data.wake = "1"` when `data.body` would be longer than 3000 characters.

## Run the tests

```sh
cd relay
bun install
bunx vitest run
bunx tsc --noEmit
```

The tests run the Worker in `workerd` with a local D1 database and local rate limiters. They mock only the outbound `fetch` to Google. They need no Cloudflare account, and they pass with the placeholder `database_id` in `wrangler.jsonc`.

## Bindings

The Worker reads these bindings. `wrangler.jsonc` declares all of them except the secret.

| Binding | Kind | What it does |
|---|---|---|
| `DB` | D1 database | Stores one row per registration. The schema is in `migrations/`. |
| `REGISTRATION_LIMITER` | Rate limiter | Allows 10 registrations per client IP per minute. |
| `PUSH_LIMITER` | Rate limiter | Allows 120 pushes per `rid` per minute. |
| `AMP_VAPID_PUBLIC_KEY` | Variable | Amp's VAPID public key. While it is empty, the relay accepts every push and logs the VAPID header it saw. When it is set, the relay rejects every push that lacks a valid VAPID signature from that key. |
| `FCM_SERVICE_ACCOUNT` | Secret | The JSON key of a Google service account that may send FCM messages in your Firebase project. The relay reads `project_id`, `client_email`, and `private_key`. Until it is set, every push to a known capability answers `500` and logs `"fcm":"config"`. |

## Deploy your own relay

You need a Cloudflare account, a Firebase project, and [Bun](https://bun.sh). Run every command from `relay/`.

1. Install the dependencies with `bun install`.
2. Sign in to Cloudflare with `bunx wrangler login`. To use an API token instead, export `CLOUDFLARE_API_TOKEN`. If the token reaches more than one account, also export `CLOUDFLARE_ACCOUNT_ID`.
3. Create the database:

	```sh
	bunx wrangler d1 create amp-push-relay
	```

	The output prints a `database_id`. Put it in `wrangler.jsonc` in place of `<your-d1-database-id>`.

4. Check the rate limiter namespace ids in `wrangler.jsonc`, `40421` and `40422`. Rate limiting namespaces are shared by every Worker in an account. If another Worker in your account uses either id, pick two unused integers.
5. Apply the migrations to the remote database:

	```sh
	bunx wrangler d1 migrations apply amp-push-relay --remote
	```

6. In the Firebase console, add an Android app with the package name `com.agentifiers.amp` to your project. Download its `google-services.json` to `app/google-services.json`. That file is gitignored.
7. In the Firebase console, open **Project settings**, then **Service accounts**, and generate a new private key. Store the downloaded JSON as the Worker secret, then delete the file:

	```sh
	bunx wrangler secret put FCM_SERVICE_ACCOUNT < path/to/service-account.json
	```

8. Deploy:

	```sh
	bunx wrangler deploy
	```

	The output prints the Worker's URL, `https://amp-push-relay.<your-subdomain>.workers.dev`.

9. Check the deployment with the smoke script. It registers, pushes, updates, and deletes, and checks that a deleted registration answers `410`. Its last push goes to a placeholder FCM token. That push answers `410` when Google accepts the service account, and `500` while `FCM_SERVICE_ACCOUNT` is unset or refused.

	```sh
	scripts/smoke.sh https://amp-push-relay.<your-subdomain>.workers.dev
	```

10. Point the app at the relay. Add the URL, with a trailing slash, to `local.properties` in the repository root, then rebuild the app:

	```properties
	amp.pushRelayUrl=https://amp-push-relay.<your-subdomain>.workers.dev/
	```

The app uses the relay only when the build has both `amp.pushRelayUrl` and `app/google-services.json`, and the device has Google Play services.

## Read the logs

```sh
bunx wrangler tail amp-push-relay --format json
```

Each request writes one JSON line with `route`, `method`, `status`, `durationMs`, and `ridPrefix`. A push line also has `bodyBytes`, `contentEncoding`, `ttl`, `urgency`, `topic`, `vapid`, and `fcm`. The relay never logs bodies, FCM tokens, secrets, capabilities, or the `Authorization` header.
