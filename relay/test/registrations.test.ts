import { afterEach, describe, expect, it, vi } from "vitest";
import { ORIGIN, captureLogs, register, registrationRow, relay, sha256Base64url } from "./helpers";

afterEach(() => {
	vi.restoreAllMocks();
});

function registrationRequest(rid: string, method: "PUT" | "DELETE", secret: string, body?: unknown): Request {
	return new Request(`${ORIGIN}/v1/registrations/${rid}`, {
		method,
		headers: { authorization: `Bearer ${secret}`, "content-type": "application/json" },
		...(body === undefined ? {} : { body: JSON.stringify(body) }),
	});
}

describe("GET /health", () => {
	it("answers 200 {ok:true}", async () => {
		const response = await relay(new Request(`${ORIGIN}/health`));
		expect(response.status).toBe(200);
		expect(await response.json()).toEqual({ ok: true });
	});
});

describe("POST /v1/registrations", () => {
	it("returns a push URL whose capability hashes to the rid and stores only the secret's hash", async () => {
		const logs = captureLogs();
		const { rid, pushUrl, secret, capability } = await register("fcm-token-register");

		expect(pushUrl).toMatch(/^https:\/\/relay\.test\/v1\/push\/[A-Za-z0-9_-]{43}$/);
		expect(rid).toBe(await sha256Base64url(capability));
		expect(secret).toMatch(/^[A-Za-z0-9_-]{43}$/);

		const row = await registrationRow(rid);
		expect(row).toEqual({
			rid,
			fcm_token: "fcm-token-register",
			secret_hash: await sha256Base64url(secret),
			created_at: expect.any(Number),
			updated_at: expect.any(Number),
			last_push_at: null,
		});
		expect(Object.values(row ?? {})).not.toContain(secret);
		expect(Object.values(row ?? {})).not.toContain(capability);

		expect(logs()).toEqual([
			{ route: "register", method: "POST", status: 201, durationMs: expect.any(Number), ridPrefix: rid.slice(0, 8) },
		]);
	});

	it("rejects a body without fcmToken with 400", async () => {
		const response = await relay(
			new Request(`${ORIGIN}/v1/registrations`, {
				method: "POST",
				headers: { "cf-connecting-ip": "203.0.113.200" },
				body: JSON.stringify({ token: "x" }),
			}),
		);
		expect(response.status).toBe(400);
		expect(await response.json()).toEqual({ error: "body must be {fcmToken}" });
	});

	it("rejects a body that is not JSON with 400", async () => {
		const response = await relay(
			new Request(`${ORIGIN}/v1/registrations`, {
				method: "POST",
				headers: { "cf-connecting-ip": "203.0.113.201" },
				body: "fcmToken=x",
			}),
		);
		expect(response.status).toBe(400);
		expect(await response.json()).toEqual({ error: "body is not JSON" });
	});

	it("answers 429 on the eleventh registration from one IP within a minute", async () => {
		const statuses: number[] = [];
		for (let attempt = 0; attempt < 11; attempt += 1) {
			const response = await relay(
				new Request(`${ORIGIN}/v1/registrations`, {
					method: "POST",
					headers: { "cf-connecting-ip": "203.0.113.7" },
					body: JSON.stringify({ fcmToken: `fcm-token-limit-${attempt}` }),
				}),
			);
			statuses.push(response.status);
		}
		expect(statuses).toEqual([201, 201, 201, 201, 201, 201, 201, 201, 201, 201, 429]);
	});
});

describe("PUT /v1/registrations/{rid}", () => {
	it("rejects a wrong secret with 401 and keeps the token", async () => {
		const { rid } = await register("fcm-token-before");
		const wrongSecret = "A".repeat(43);

		const response = await relay(registrationRequest(rid, "PUT", wrongSecret, { fcmToken: "fcm-token-attacker" }));

		expect(response.status).toBe(401);
		expect((await registrationRow(rid))?.fcm_token).toBe("fcm-token-before");
	});

	it("replaces the FCM token with the right secret", async () => {
		const { rid, secret } = await register("fcm-token-old");

		const response = await relay(registrationRequest(rid, "PUT", secret, { fcmToken: "fcm-token-new" }));

		expect(response.status).toBe(204);
		expect((await registrationRow(rid))?.fcm_token).toBe("fcm-token-new");
	});

	it("answers 404 for an unknown rid", async () => {
		const { secret } = await register("fcm-token-unknown-rid");
		const response = await relay(registrationRequest("B".repeat(43), "PUT", secret, { fcmToken: "x" }));
		expect(response.status).toBe(404);
	});
});

describe("DELETE /v1/registrations/{rid}", () => {
	it("rejects a wrong secret with 401 and keeps the row", async () => {
		const { rid, secret } = await register("fcm-token-delete-wrong");
		const wrongSecret = `${secret.slice(0, 42)}${secret.endsWith("A") ? "B" : "A"}`;

		const response = await relay(registrationRequest(rid, "DELETE", wrongSecret));

		expect(response.status).toBe(401);
		expect((await registrationRow(rid))?.fcm_token).toBe("fcm-token-delete-wrong");
	});

	it("deletes the row with the right secret, then answers 404", async () => {
		const { rid, secret } = await register("fcm-token-delete");
		expect((await registrationRow(rid))?.fcm_token).toBe("fcm-token-delete");

		const first = await relay(registrationRequest(rid, "DELETE", secret));
		expect(first.status).toBe(204);
		expect(await registrationRow(rid)).toBeNull();

		const second = await relay(registrationRequest(rid, "DELETE", secret));
		expect(second.status).toBe(404);
	});
});
