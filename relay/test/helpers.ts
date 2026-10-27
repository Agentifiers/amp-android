import { env } from "cloudflare:workers";
import { vi } from "vitest";
import { z } from "zod";
import worker from "../src/index";

const RegisteredBody = z.object({ rid: z.string(), pushUrl: z.string(), secret: z.string() });

export const ORIGIN = "https://relay.test";
export const TOKEN_URL = "https://oauth2.googleapis.com/token";

type EnvOverrides = { AMP_VAPID_PUBLIC_KEY?: string; FCM_SERVICE_ACCOUNT?: string };

export function relay(request: Request, overrides: EnvOverrides = {}): Promise<Response> {
	return worker.fetch(request, { ...env, ...overrides });
}

export function base64url(bytes: Uint8Array): string {
	return btoa(String.fromCharCode(...bytes)).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

export function fromBase64url(text: string): Uint8Array {
	const base64 = text.replaceAll("-", "+").replaceAll("_", "/");
	return Uint8Array.from(atob(base64 + "=".repeat((4 - (base64.length % 4)) % 4)), (char) => char.charCodeAt(0));
}

export async function sha256Base64url(text: string): Promise<string> {
	return base64url(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text))));
}

let nextIp = 0;

export async function register(fcmToken: string): Promise<{ rid: string; pushUrl: string; secret: string; capability: string }> {
	nextIp += 1;
	const response = await relay(
		new Request(`${ORIGIN}/v1/registrations`, {
			method: "POST",
			headers: { "content-type": "application/json", "cf-connecting-ip": `198.51.100.${nextIp}` },
			body: JSON.stringify({ fcmToken }),
		}),
	);
	if (response.status !== 201) throw new Error(`registration answered ${response.status}`);
	const body = RegisteredBody.parse(await response.json());
	return { ...body, capability: body.pushUrl.slice(`${ORIGIN}/v1/push/`.length) };
}

export function pushRequest(capability: string, body: Uint8Array, headers: Record<string, string> = {}): Request {
	return new Request(`${ORIGIN}/v1/push/${capability}`, {
		method: "POST",
		headers: { "content-encoding": "aes128gcm", ttl: "60", ...headers },
		body,
	});
}

export async function registrationRow(rid: string): Promise<Record<string, unknown> | null> {
	return env.DB.prepare("SELECT * FROM registrations WHERE rid = ?").bind(rid).first();
}

export async function generateKeyPair(algorithm: Parameters<typeof crypto.subtle.generateKey>[0]): Promise<CryptoKeyPair> {
	const pair = await crypto.subtle.generateKey(algorithm, true, ["sign", "verify"]);
	if (!("privateKey" in pair)) throw new Error("generateKey returned a single key");
	return pair;
}

export async function exportBytes(format: "pkcs8" | "raw", key: CryptoKey): Promise<Uint8Array> {
	const exported = await crypto.subtle.exportKey(format, key);
	if (!(exported instanceof ArrayBuffer)) throw new Error(`${format} export is not an ArrayBuffer`);
	return new Uint8Array(exported);
}

const rsaKeys = await generateKeyPair({
	name: "RSASSA-PKCS1-v1_5",
	modulusLength: 2048,
	publicExponent: new Uint8Array([1, 0, 1]),
	hash: "SHA-256",
});
const pkcs8 = await exportBytes("pkcs8", rsaKeys.privateKey);
const pem = `-----BEGIN PRIVATE KEY-----\n${btoa(String.fromCharCode(...pkcs8)).replace(/.{64}/g, "$&\n")}\n-----END PRIVATE KEY-----\n`;

export const serviceAccountPublicKey = rsaKeys.publicKey;

export function serviceAccount(projectId: string): string {
	return JSON.stringify({
		type: "service_account",
		project_id: projectId,
		private_key_id: "test-key-id",
		private_key: pem,
		client_email: `relay@${projectId}.iam.gserviceaccount.com`,
		token_uri: TOKEN_URL,
	});
}

export type GoogleCall = { url: string; authorization: string | null; body: string };

export function mockGoogle(fcmAnswer: () => Response | Promise<Response>): GoogleCall[] {
	const calls: GoogleCall[] = [];
	vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
		const request = new Request(input, init);
		const body = new TextDecoder().decode(await request.arrayBuffer());
		calls.push({ url: request.url, authorization: request.headers.get("authorization"), body });
		if (request.url === TOKEN_URL) return Response.json({ access_token: "test-access-token", expires_in: 3600 });
		if (request.url.startsWith("https://fcm.googleapis.com/")) return fcmAnswer();
		throw new Error(`unexpected fetch ${request.url}`);
	});
	return calls;
}

export function captureLogs(): () => unknown[] {
	const spy = vi.spyOn(console, "log").mockImplementation(() => {});
	return () => spy.mock.calls.map(([line]) => JSON.parse(String(line)));
}
