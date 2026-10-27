import { afterEach, describe, expect, it, vi } from "vitest";
import {
	base64url,
	captureLogs,
	exportBytes,
	generateKeyPair,
	mockGoogle,
	pushRequest,
	register,
	relay,
	serviceAccount,
} from "./helpers";

afterEach(() => {
	vi.restoreAllMocks();
});

type KeyPair = { privateKey: CryptoKey; publicKey: string };

async function vapidKeyPair(): Promise<KeyPair> {
	const pair = await generateKeyPair({ name: "ECDSA", namedCurve: "P-256" });
	return { privateKey: pair.privateKey, publicKey: base64url(await exportBytes("raw", pair.publicKey)) };
}

async function vapidJwt(signer: CryptoKey, claims: object): Promise<string> {
	const encode = (value: object) => base64url(new TextEncoder().encode(JSON.stringify(value)));
	const signingInput = `${encode({ typ: "JWT", alg: "ES256" })}.${encode(claims)}`;
	const signature = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, signer, new TextEncoder().encode(signingInput));
	return `${signingInput}.${base64url(new Uint8Array(signature))}`;
}

const amp = await vapidKeyPair();
const stranger = await vapidKeyPair();
const nowSeconds = () => Math.floor(Date.now() / 1000);
const validClaims = () => ({ aud: "https://relay.test", exp: nowSeconds() + 3600, sub: "mailto:ops@ampcode.com" });
const PINNED = { AMP_VAPID_PUBLIC_KEY: amp.publicKey, FCM_SERVICE_ACCOUNT: serviceAccount("amp-vapid") };

async function pushWith(authorization: string | null, extraHeaders: Record<string, string> = {}): Promise<number> {
	const { capability } = await register("fcm-token-vapid");
	const headers = authorization === null ? extraHeaders : { authorization, ...extraHeaders };
	return (await relay(pushRequest(capability, new Uint8Array(16), headers), PINNED)).status;
}

describe("VAPID with AMP_VAPID_PUBLIC_KEY configured", () => {
	it("accepts a header signed by the configured key", async () => {
		mockGoogle(() => Response.json({ name: "projects/amp-vapid/messages/1" }));
		const jwt = await vapidJwt(amp.privateKey, validClaims());
		expect(await pushWith(`vapid t=${jwt}, k=${amp.publicKey}`)).toBe(201);
	});

	it("accepts the legacy WebPush scheme with Crypto-Key", async () => {
		mockGoogle(() => Response.json({ name: "projects/amp-vapid/messages/2" }));
		const jwt = await vapidJwt(amp.privateKey, validClaims());
		expect(await pushWith(`WebPush ${jwt}`, { "crypto-key": `dh=abc;p256ecdsa=${amp.publicKey}` })).toBe(201);
	});

	const rejected: { name: string; authorization: () => Promise<string | null> }[] = [
		{ name: "no Authorization header", authorization: async () => null },
		{
			name: "a k other than the configured key, even with a JWT the configured key signed",
			authorization: async () => `vapid t=${await vapidJwt(amp.privateKey, validClaims())}, k=${stranger.publicKey}`,
		},
		{
			name: "a JWT signed by another key under the configured k",
			authorization: async () => `vapid t=${await vapidJwt(stranger.privateKey, validClaims())}, k=${amp.publicKey}`,
		},
		{
			name: "an expired JWT",
			authorization: async () =>
				`vapid t=${await vapidJwt(amp.privateKey, { ...validClaims(), exp: nowSeconds() - 1 })}, k=${amp.publicKey}`,
		},
		{
			name: "an aud for another origin",
			authorization: async () =>
				`vapid t=${await vapidJwt(amp.privateKey, { ...validClaims(), aud: "https://ntfy.sh" })}, k=${amp.publicKey}`,
		},
		{ name: "a malformed JWT", authorization: async () => `vapid t=not-a-jwt, k=${amp.publicKey}` },
	];

	for (const { name, authorization } of rejected) {
		it(`answers 403 for ${name}`, async () => {
			const calls = mockGoogle(() => Response.json({ name: "projects/amp-vapid/messages/3" }));
			expect(await pushWith(await authorization())).toBe(403);
			expect(calls).toEqual([]);
		});
	}
});

describe("VAPID with AMP_VAPID_PUBLIC_KEY empty", () => {
	it("forwards a push with an unverifiable header and logs what was presented", async () => {
		const { capability } = await register("fcm-token-open");
		mockGoogle(() => Response.json({ name: "projects/amp-open/messages/1" }));
		const logs = captureLogs();
		const claims = { aud: "https://relay.test", exp: 1_900_000_000 };
		const jwt = await vapidJwt(stranger.privateKey, claims);

		const response = await relay(
			pushRequest(capability, new Uint8Array(16), { authorization: `vapid t=${jwt}, k=${stranger.publicKey}` }),
			{ FCM_SERVICE_ACCOUNT: serviceAccount("amp-open") },
		);

		expect(response.status).toBe(201);
		expect(logs()).toEqual([
			expect.objectContaining({
				route: "push",
				status: 201,
				vapid: { kind: "present", scheme: "vapid", key: stranger.publicKey, aud: "https://relay.test", exp: 1_900_000_000 },
				fcm: "sent",
			}),
		]);
	});

	it("logs an unparseable header as invalid and still forwards", async () => {
		const { capability } = await register("fcm-token-open-invalid");
		mockGoogle(() => Response.json({ name: "projects/amp-open/messages/2" }));
		const logs = captureLogs();

		const response = await relay(pushRequest(capability, new Uint8Array(16), { authorization: "Bearer abc" }), {
			FCM_SERVICE_ACCOUNT: serviceAccount("amp-open"),
		});

		expect(response.status).toBe(201);
		expect(logs()).toEqual([
			expect.objectContaining({ status: 201, vapid: { kind: "invalid", reason: "unsupported authorization scheme" } }),
		]);
	});
});
