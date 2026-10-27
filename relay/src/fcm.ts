import { z } from "zod";
import { encodeBase64url } from "./base64url";
import type { FcmToken, Rid } from "./ids";

export type FcmOutcome =
	| { kind: "sent" }
	| { kind: "gone" }
	| { kind: "retry"; retryAfterSeconds: number }
	| { kind: "config"; reason: string };

type FcmMessage = {
	token: FcmToken;
	data: { v: "1"; rid: Rid } & ({ body: string } | { wake: "1" });
	android: { priority: "HIGH"; ttl?: string; collapse_key?: string };
};

const MAX_BODY_CHARS = 3000;
const MAX_FCM_TTL_SECONDS = 2_419_200;
const RETRY_AFTER_SECONDS = 30;
const TOKEN_REFRESH_MARGIN_MS = 5 * 60 * 1000;
const TOKEN_URL = "https://oauth2.googleapis.com/token";
const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

const RETRY = { kind: "retry", retryAfterSeconds: RETRY_AFTER_SECONDS } satisfies FcmOutcome;

const FcmErrorKey = z.enum([
	"UNREGISTERED",
	"INVALID_ARGUMENT_TOKEN",
	"INVALID_ARGUMENT",
	"QUOTA_EXCEEDED",
	"UNAVAILABLE",
	"INTERNAL",
	"SENDER_ID_MISMATCH",
	"THIRD_PARTY_AUTH_ERROR",
	"UNAUTHENTICATED",
	"PERMISSION_DENIED",
	"UNSPECIFIED_ERROR",
]);
type FcmErrorKey = z.infer<typeof FcmErrorKey>;

const OUTCOME_BY_FCM_ERROR: Record<FcmErrorKey, FcmOutcome> = {
	UNREGISTERED: { kind: "gone" },
	INVALID_ARGUMENT_TOKEN: { kind: "gone" },
	INVALID_ARGUMENT: { kind: "config", reason: "fcm rejected the message as invalid" },
	QUOTA_EXCEEDED: RETRY,
	UNAVAILABLE: RETRY,
	INTERNAL: RETRY,
	SENDER_ID_MISMATCH: { kind: "config", reason: "fcm sender id mismatch" },
	THIRD_PARTY_AUTH_ERROR: { kind: "config", reason: "fcm third party auth error" },
	UNAUTHENTICATED: { kind: "config", reason: "google rejected the access token" },
	PERMISSION_DENIED: { kind: "config", reason: "service account lacks fcm permission" },
	UNSPECIFIED_ERROR: { kind: "config", reason: "unrecognized fcm error" },
};

const GoogleError = z.object({
	error: z.object({
		status: z.string().optional(),
		details: z
			.array(
				z.object({
					"@type": z.string(),
					errorCode: z.string().optional(),
					fieldViolations: z.array(z.object({ field: z.string() })).optional(),
				}),
			)
			.optional(),
	}),
});

const ServiceAccount = z.object({
	project_id: z.string().min(1),
	client_email: z.string().min(1),
	private_key: z.string().min(1),
});
type ServiceAccount = z.infer<typeof ServiceAccount>;

const TokenResponse = z.object({ access_token: z.string().min(1), expires_in: z.number().positive() });

type AccessToken = { kind: "token"; value: string } | Extract<FcmOutcome, { kind: "retry" | "config" }>;

let cachedToken: { serviceAccountJson: string; value: string; refreshAt: number } | null = null;

export function buildMessage(push: {
	token: FcmToken;
	rid: Rid;
	body: Uint8Array;
	ttl: string | null;
	topic: string | null;
}): FcmMessage {
	const body = encodeBase64url(push.body);
	const ttlSeconds = push.ttl !== null && /^\d+$/.test(push.ttl) ? Math.min(Number(push.ttl), MAX_FCM_TTL_SECONDS) : null;
	return {
		token: push.token,
		data: body.length > MAX_BODY_CHARS ? { v: "1", rid: push.rid, wake: "1" } : { v: "1", rid: push.rid, body },
		android: {
			priority: "HIGH",
			...(ttlSeconds === null ? {} : { ttl: `${ttlSeconds}s` }),
			...(push.topic === null || push.topic === "" ? {} : { collapse_key: push.topic }),
		},
	};
}

type ParsedServiceAccount =
	| { kind: "account"; account: ServiceAccount; json: string }
	| Extract<FcmOutcome, { kind: "config" }>;

function parseServiceAccount(json: string | undefined): ParsedServiceAccount {
	if (json === undefined || json === "") return { kind: "config", reason: "FCM_SERVICE_ACCOUNT is not set" };
	let raw: unknown;
	try {
		raw = JSON.parse(json);
	} catch {
		return { kind: "config", reason: "FCM_SERVICE_ACCOUNT is not JSON" };
	}
	const account = ServiceAccount.safeParse(raw);
	return account.success
		? { kind: "account", account: account.data, json }
		: { kind: "config", reason: "FCM_SERVICE_ACCOUNT lacks project_id, client_email, or private_key" };
}

async function signAssertion(account: ServiceAccount, nowSeconds: number): Promise<string | null> {
	let key: CryptoKey;
	try {
		const pem = account.private_key.replace(/-----(BEGIN|END) PRIVATE KEY-----|\s+/g, "");
		const der = Uint8Array.from(atob(pem), (char) => char.charCodeAt(0));
		key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
	} catch {
		return null;
	}
	const encodeJson = (value: object) => encodeBase64url(new TextEncoder().encode(JSON.stringify(value)));
	const signingInput = `${encodeJson({ alg: "RS256", typ: "JWT" })}.${encodeJson({
		iss: account.client_email,
		scope: FCM_SCOPE,
		aud: TOKEN_URL,
		iat: nowSeconds,
		exp: nowSeconds + 3600,
	})}`;
	const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(signingInput));
	return `${signingInput}.${encodeBase64url(new Uint8Array(signature))}`;
}

async function accessToken(parsed: Extract<ParsedServiceAccount, { kind: "account" }>, now: number): Promise<AccessToken> {
	if (cachedToken !== null && cachedToken.serviceAccountJson === parsed.json && now < cachedToken.refreshAt) {
		return { kind: "token", value: cachedToken.value };
	}
	const assertion = await signAssertion(parsed.account, Math.floor(now / 1000));
	if (assertion === null) return { kind: "config", reason: "FCM_SERVICE_ACCOUNT private_key is not a PKCS8 RSA key" };
	let response: Response;
	try {
		response = await fetch(TOKEN_URL, {
			method: "POST",
			headers: { "content-type": "application/x-www-form-urlencoded" },
			body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
		});
	} catch {
		return RETRY;
	}
	if (response.status >= 500) return RETRY;
	const token = TokenResponse.safeParse(await response.json().catch(() => null));
	if (!response.ok || !token.success) {
		return { kind: "config", reason: `google token endpoint answered ${response.status}` };
	}
	cachedToken = {
		serviceAccountJson: parsed.json,
		value: token.data.access_token,
		refreshAt: now + token.data.expires_in * 1000 - TOKEN_REFRESH_MARGIN_MS,
	};
	return { kind: "token", value: token.data.access_token };
}

function errorKey(httpStatus: number, body: unknown): FcmErrorKey {
	const parsed = GoogleError.safeParse(body);
	const details = parsed.success ? (parsed.data.error.details ?? []) : [];
	const code =
		details.find((detail) => detail["@type"].endsWith("google.firebase.fcm.v1.FcmError"))?.errorCode ??
		(parsed.success ? parsed.data.error.status : undefined);
	const aboutToken = details.flatMap((detail) => detail.fieldViolations ?? []).every((violation) => violation.field === "message.token");
	const known = FcmErrorKey.safeParse(code === "INVALID_ARGUMENT" && aboutToken ? "INVALID_ARGUMENT_TOKEN" : code);
	if (known.success) return known.data;
	if (httpStatus === 401) return "UNAUTHENTICATED";
	if (httpStatus === 429 || httpStatus >= 500) return "UNAVAILABLE";
	return "UNSPECIFIED_ERROR";
}

export async function sendToFcm(request: {
	serviceAccountJson: string | undefined;
	message: FcmMessage;
	now: number;
}): Promise<FcmOutcome> {
	const parsed = parseServiceAccount(request.serviceAccountJson);
	if (parsed.kind === "config") return parsed;
	const token = await accessToken(parsed, request.now);
	if (token.kind !== "token") return token;
	let response: Response;
	try {
		response = await fetch(`https://fcm.googleapis.com/v1/projects/${parsed.account.project_id}/messages:send`, {
			method: "POST",
			headers: { authorization: `Bearer ${token.value}`, "content-type": "application/json" },
			body: JSON.stringify({ message: request.message }),
		});
	} catch {
		return RETRY;
	}
	if (response.ok) {
		await response.body?.cancel();
		return { kind: "sent" };
	}
	if (response.status === 401) cachedToken = null;
	return OUTCOME_BY_FCM_ERROR[errorKey(response.status, await response.json().catch(() => null))];
}
