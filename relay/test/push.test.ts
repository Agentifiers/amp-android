import { afterEach, describe, expect, it, vi } from "vitest";
import { z } from "zod";
import {
	TOKEN_URL,
	base64url,
	captureLogs,
	fromBase64url,
	mockGoogle,
	pushRequest,
	register,
	registrationRow,
	relay,
	serviceAccount,
	serviceAccountPublicKey,
} from "./helpers";

afterEach(() => {
	vi.restoreAllMocks();
});

const FCM = { FCM_SERVICE_ACCOUNT: serviceAccount("amp-test") };
const SEND_URL = "https://fcm.googleapis.com/v1/projects/amp-test/messages:send";

function fcmError(status: number, error: object): () => Response {
	return () => Response.json({ error }, { status });
}

function fcmDetail(errorCode: string): object {
	return { "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError", errorCode };
}

const SentMessage = z.object({
	message: z.object({
		token: z.string(),
		data: z.record(z.string(), z.string()),
		android: z.record(z.string(), z.string()),
	}),
});

function sentMessages(calls: { url: string; body: string }[]): z.infer<typeof SentMessage>[] {
	return calls.filter((call) => call.url === SEND_URL).map((call) => SentMessage.parse(JSON.parse(call.body)));
}

describe("POST /v1/push/{capability}", () => {
	it("forwards the body byte for byte as base64url with TTL and Topic mapped", async () => {
		const { rid, capability } = await register("fcm-token-forward");
		const body = crypto.getRandomValues(new Uint8Array(525));
		const calls = mockGoogle(() => Response.json({ name: "projects/amp-test/messages/1" }));

		const response = await relay(pushRequest(capability, body, { ttl: "86400", topic: "thread-1", urgency: "high" }), FCM);

		expect(response.status).toBe(201);
		const send = calls.find((call) => call.url === SEND_URL);
		expect(send?.authorization).toBe("Bearer test-access-token");
		const [message] = sentMessages(calls);
		expect(message).toEqual({
			message: {
				token: "fcm-token-forward",
				data: { v: "1", rid, body: base64url(body) },
				android: { priority: "HIGH", ttl: "86400s", collapse_key: "thread-1" },
			},
		});
		expect(fromBase64url(message?.message.data.body ?? "")).toEqual(body);
		expect((await registrationRow(rid))?.last_push_at).toEqual(expect.any(Number));
	});

	it("uses the URL-safe alphabet without padding and omits absent TTL and Topic", async () => {
		const { rid, capability } = await register("fcm-token-alphabet");
		const calls = mockGoogle(() => Response.json({ name: "projects/amp-test/messages/2" }));
		const request = new Request(`https://relay.test/v1/push/${capability}`, {
			method: "POST",
			headers: { "content-encoding": "aes128gcm" },
			body: new Uint8Array([0xfb, 0xef, 0xff, 0x01]),
		});

		const response = await relay(request, FCM);

		expect(response.status).toBe(201);
		expect(sentMessages(calls)).toEqual([
			{ message: { token: "fcm-token-alphabet", data: { v: "1", rid, body: "--__AQ" }, android: { priority: "HIGH" } } },
		]);
	});

	it("sends body for 2250 bytes (3000 characters) and wake for 2251 bytes", async () => {
		const { rid, capability } = await register("fcm-token-wake");
		const calls = mockGoogle(() => Response.json({ name: "projects/amp-test/messages/3" }));

		const fits = await relay(pushRequest(capability, new Uint8Array(2250)), FCM);
		const tooBig = await relay(pushRequest(capability, new Uint8Array(2251)), FCM);

		expect([fits.status, tooBig.status]).toEqual([201, 201]);
		const datas = sentMessages(calls).map((sent) => sent.message.data);
		expect(datas).toEqual([
			{ v: "1", rid, body: "A".repeat(3000) },
			{ v: "1", rid, wake: "1" },
		]);
	});

	it("answers 410 for an unknown capability without calling FCM", async () => {
		const calls = mockGoogle(() => Response.json({}));
		const response = await relay(pushRequest("C".repeat(43), new Uint8Array(16)), FCM);
		expect(response.status).toBe(410);
		expect(calls).toEqual([]);
	});

	it("answers 413 above 4096 bytes and accepts exactly 4096", async () => {
		const { capability } = await register("fcm-token-size");
		mockGoogle(() => Response.json({ name: "projects/amp-test/messages/4" }));

		const atLimit = await relay(pushRequest(capability, new Uint8Array(4096)), FCM);
		const overLimit = await relay(pushRequest(capability, new Uint8Array(4097)), FCM);

		expect([atLimit.status, overLimit.status]).toEqual([201, 413]);
	});

	it("answers 400 without Content-Encoding: aes128gcm", async () => {
		const { capability } = await register("fcm-token-encoding");
		const response = await relay(pushRequest(capability, new Uint8Array(16), { "content-encoding": "aesgcm" }), FCM);
		expect(response.status).toBe(400);
		expect(await response.json()).toEqual({ error: "content-encoding must be aes128gcm" });
	});

	it("deletes the row and answers 410 when FCM says UNREGISTERED", async () => {
		const { rid, capability } = await register("fcm-token-unregistered");
		expect((await registrationRow(rid))?.fcm_token).toBe("fcm-token-unregistered");
		mockGoogle(fcmError(404, { code: 404, status: "NOT_FOUND", details: [fcmDetail("UNREGISTERED")] }));

		const response = await relay(pushRequest(capability, new Uint8Array(16)), FCM);

		expect(response.status).toBe(410);
		expect(await registrationRow(rid)).toBeNull();
	});

	const cases: { name: string; answer: () => Response | Promise<Response>; status: number; retryAfter: string | null; rowKept: boolean }[] = [
		{
			name: "INVALID_ARGUMENT about the token",
			answer: fcmError(400, {
				status: "INVALID_ARGUMENT",
				details: [
					fcmDetail("INVALID_ARGUMENT"),
					{ "@type": "type.googleapis.com/google.rpc.BadRequest", fieldViolations: [{ field: "message.token" }] },
				],
			}),
			status: 410,
			retryAfter: null,
			rowKept: false,
		},
		{
			name: "INVALID_ARGUMENT with only the FcmError detail, as Google documents an invalid token",
			answer: fcmError(400, {
				message: "The registration token is not a valid FCM registration token",
				status: "INVALID_ARGUMENT",
				details: [fcmDetail("INVALID_ARGUMENT")],
			}),
			status: 410,
			retryAfter: null,
			rowKept: false,
		},
		{
			name: "INVALID_ARGUMENT about another field",
			answer: fcmError(400, {
				status: "INVALID_ARGUMENT",
				details: [
					fcmDetail("INVALID_ARGUMENT"),
					{ "@type": "type.googleapis.com/google.rpc.BadRequest", fieldViolations: [{ field: "message.android.ttl" }] },
				],
			}),
			status: 500,
			retryAfter: null,
			rowKept: true,
		},
		{ name: "QUOTA_EXCEEDED", answer: fcmError(429, { status: "RESOURCE_EXHAUSTED", details: [fcmDetail("QUOTA_EXCEEDED")] }), status: 503, retryAfter: "30", rowKept: true },
		{ name: "UNAVAILABLE", answer: fcmError(503, { status: "UNAVAILABLE", details: [fcmDetail("UNAVAILABLE")] }), status: 503, retryAfter: "30", rowKept: true },
		{ name: "INTERNAL", answer: fcmError(500, { status: "INTERNAL", details: [fcmDetail("INTERNAL")] }), status: 503, retryAfter: "30", rowKept: true },
		{ name: "a 502 without a JSON body", answer: () => new Response("<html>bad gateway</html>", { status: 502 }), status: 503, retryAfter: "30", rowKept: true },
		{
			name: "a network error",
			answer: () => Promise.reject(new TypeError("Network connection lost.")),
			status: 503,
			retryAfter: "30",
			rowKept: true,
		},
		{ name: "SENDER_ID_MISMATCH", answer: fcmError(403, { status: "PERMISSION_DENIED", details: [fcmDetail("SENDER_ID_MISMATCH")] }), status: 500, retryAfter: null, rowKept: true },
		{ name: "THIRD_PARTY_AUTH_ERROR", answer: fcmError(401, { status: "UNAUTHENTICATED", details: [fcmDetail("THIRD_PARTY_AUTH_ERROR")] }), status: 500, retryAfter: null, rowKept: true },
		{ name: "a 401 from Google", answer: fcmError(401, { code: 401, status: "UNAUTHENTICATED" }), status: 500, retryAfter: null, rowKept: true },
	];

	for (const { name, answer, status, retryAfter, rowKept } of cases) {
		it(`maps FCM ${name} to ${status}`, async () => {
			const { rid, capability } = await register(`fcm-token-${name}`);
			mockGoogle(answer);

			const response = await relay(pushRequest(capability, new Uint8Array(16)), FCM);

			expect(response.status).toBe(status);
			expect(response.headers.get("retry-after")).toBe(retryAfter);
			expect((await registrationRow(rid))?.fcm_token ?? null).toBe(rowKept ? `fcm-token-${name}` : null);
		});
	}

	it("mints the OAuth token with an RS256 JWT signed by the service account key", async () => {
		const { capability } = await register("fcm-token-oauth");
		const calls = mockGoogle(() => Response.json({ name: "projects/amp-oauth/messages/5" }));
		const before = Math.floor(Date.now() / 1000);

		const response = await relay(pushRequest(capability, new Uint8Array(16)), {
			FCM_SERVICE_ACCOUNT: serviceAccount("amp-oauth"),
		});

		expect(response.status).toBe(201);
		const tokenCall = calls.find((call) => call.url === TOKEN_URL);
		const form = new URLSearchParams(tokenCall?.body ?? "");
		expect(form.get("grant_type")).toBe("urn:ietf:params:oauth:grant-type:jwt-bearer");
		const [header = "", claims = "", signature = ""] = (form.get("assertion") ?? "").split(".");
		expect(JSON.parse(new TextDecoder().decode(fromBase64url(header)))).toEqual({ alg: "RS256", typ: "JWT" });
		const payload = JSON.parse(new TextDecoder().decode(fromBase64url(claims)));
		expect(payload).toEqual({
			iss: "relay@amp-oauth.iam.gserviceaccount.com",
			scope: "https://www.googleapis.com/auth/firebase.messaging",
			aud: "https://oauth2.googleapis.com/token",
			iat: expect.any(Number),
			exp: payload.iat + 3600,
		});
		expect(payload.iat).toBeGreaterThanOrEqual(before);
		const verified = await crypto.subtle.verify(
			"RSASSA-PKCS1-v1_5",
			serviceAccountPublicKey,
			fromBase64url(signature),
			new TextEncoder().encode(`${header}.${claims}`),
		);
		expect(verified).toBe(true);
		expect(calls.map((call) => call.url)).toEqual([TOKEN_URL, "https://fcm.googleapis.com/v1/projects/amp-oauth/messages:send"]);
	});

	it("answers 429 on the 121st push to one registration within a minute", async () => {
		const capability = "D".repeat(43);
		const statuses: number[] = [];
		for (let attempt = 0; attempt < 121; attempt += 1) {
			statuses.push((await relay(pushRequest(capability, new Uint8Array(16)))).status);
		}
		expect(statuses).toEqual([...Array<number>(120).fill(410), 429]);
	});

	it("answers 500 with FCM unconfigured and still logs the VAPID and header fields", async () => {
		const { rid, capability } = await register("fcm-token-unconfigured");
		const logs = captureLogs();
		const vapid = {
			kind: "present",
			scheme: "vapid",
			key: "BKxK9cG2Kz0-unverified-key",
			aud: "https://relay.test",
			exp: 1_900_000_000,
			sub: "mailto:ops@ampcode.com",
		};
		const jwt = [
			base64url(new TextEncoder().encode(JSON.stringify({ typ: "JWT", alg: "ES256" }))),
			base64url(new TextEncoder().encode(JSON.stringify({ aud: vapid.aud, exp: vapid.exp, sub: vapid.sub }))),
			base64url(new Uint8Array(64)),
		].join(".");

		const response = await relay(
			pushRequest(capability, new Uint8Array([1, 2, 3]), {
				ttl: "2419200",
				urgency: "normal",
				topic: "idle",
				authorization: `vapid t=${jwt}, k=${vapid.key}`,
			}),
		);

		expect(response.status).toBe(500);
		expect(logs()).toEqual([
			{
				route: "push",
				method: "POST",
				status: 500,
				durationMs: expect.any(Number),
				ridPrefix: rid.slice(0, 8),
				error: "FCM_SERVICE_ACCOUNT is not set",
				bodyBytes: 3,
				contentEncoding: "aes128gcm",
				ttl: "2419200",
				urgency: "normal",
				topic: "idle",
				vapid,
				fcm: "config",
			},
		]);
		expect(JSON.stringify(logs())).not.toContain(jwt);
	});
});
