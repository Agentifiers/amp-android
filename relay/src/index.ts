import { z } from "zod";
import { buildMessage, sendToFcm, type FcmOutcome } from "./fcm";
import {
	Capability,
	FcmToken,
	RegistrationSecret,
	Rid,
	hashSecret,
	newCapability,
	newRegistrationSecret,
	ridOf,
} from "./ids";
import { deleteRegistration, findRegistration, insertRegistration, markPushed, updateFcmToken } from "./registrations";
import { parseVapid, verifyVapid, type VapidCheck, type VapidClaims, type VapidScheme } from "./vapid";

type RelayEnv = Env & { FCM_SERVICE_ACCOUNT?: string };

const MAX_PUSH_BYTES = 4096;
const MAX_JSON_BYTES = 8192;

type Reply =
	| FcmOutcome
	| { kind: "healthy" }
	| { kind: "registered"; rid: Rid; pushUrl: string; secret: RegistrationSecret }
	| { kind: "noContent" }
	| { kind: "badRequest"; reason: string }
	| { kind: "unauthorized" }
	| { kind: "forbidden"; reason: string }
	| { kind: "notFound" }
	| { kind: "tooLarge" }
	| { kind: "rateLimited" }
	| { kind: "failed"; reason: string };

function toResponse(reply: Reply): Response {
	switch (reply.kind) {
		case "healthy":
			return Response.json({ ok: true });
		case "registered":
			return Response.json({ rid: reply.rid, pushUrl: reply.pushUrl, secret: reply.secret }, { status: 201 });
		case "sent":
			return new Response(null, { status: 201 });
		case "noContent":
			return new Response(null, { status: 204 });
		case "badRequest":
			return Response.json({ error: reply.reason }, { status: 400 });
		case "unauthorized":
			return new Response(null, { status: 401 });
		case "forbidden":
			return new Response(null, { status: 403 });
		case "notFound":
			return new Response(null, { status: 404 });
		case "gone":
			return new Response(null, { status: 410 });
		case "tooLarge":
			return new Response(null, { status: 413 });
		case "rateLimited":
			return new Response(null, { status: 429 });
		case "config":
		case "failed":
			return new Response(null, { status: 500 });
		case "retry":
			return new Response(null, { status: 503, headers: { "retry-after": String(reply.retryAfterSeconds) } });
		default: {
			const _exhaustive: never = reply;
			return _exhaustive;
		}
	}
}

type VapidLog =
	| Exclude<VapidCheck, { kind: "present" }>
	| ({ kind: "present"; scheme: VapidScheme; key: string } & VapidClaims);

type PushLog = {
	bodyBytes: number;
	contentEncoding: string | null;
	ttl: string | null;
	urgency: string | null;
	topic: string | null;
	vapid: VapidLog;
	fcm?: FcmOutcome["kind"];
};

type Handled = { reply: Reply; rid?: Rid; push?: PushLog };

type RouteName = "health" | "register" | "update" | "delete" | "push" | "unmatched";

type Context = {
	request: Request;
	env: RelayEnv;
	url: URL;
	params: Readonly<Record<string, string | undefined>>;
	now: number;
};

function log(entry: { route: RouteName; method: string; status: number; started: number; handled: Handled }): void {
	const { handled } = entry;
	console.log(
		JSON.stringify({
			route: entry.route,
			method: entry.method,
			status: entry.status,
			durationMs: Date.now() - entry.started,
			...(handled.rid === undefined ? {} : { ridPrefix: handled.rid.slice(0, 8) }),
			...("reason" in handled.reply ? { error: handled.reply.reason } : {}),
			...handled.push,
		}),
	);
}

async function readBounded(
	request: Request,
	limit: number,
): Promise<{ kind: "body"; bytes: Uint8Array } | { kind: "tooLarge"; bytesRead: number }> {
	if (request.body === null) return { kind: "body", bytes: new Uint8Array() };
	const reader = request.body.getReader();
	const chunks: Uint8Array[] = [];
	let total = 0;
	for (;;) {
		const { done, value } = await reader.read();
		if (done) break;
		total += value.byteLength;
		if (total > limit) {
			await reader.cancel();
			return { kind: "tooLarge", bytesRead: total };
		}
		chunks.push(value);
	}
	const bytes = new Uint8Array(total);
	let offset = 0;
	for (const chunk of chunks) {
		bytes.set(chunk, offset);
		offset += chunk.byteLength;
	}
	return { kind: "body", bytes };
}

async function parseJsonBody<T>(
	request: Request,
	schema: z.ZodType<T>,
): Promise<{ kind: "parsed"; value: T } | Extract<Reply, { kind: "badRequest" | "tooLarge" }>> {
	const body = await readBounded(request, MAX_JSON_BYTES);
	if (body.kind === "tooLarge") return { kind: "tooLarge" };
	let raw: unknown;
	try {
		raw = JSON.parse(new TextDecoder().decode(body.bytes));
	} catch {
		return { kind: "badRequest", reason: "body is not JSON" };
	}
	const parsed = schema.safeParse(raw);
	return parsed.success ? { kind: "parsed", value: parsed.data } : { kind: "badRequest", reason: "body must be {fcmToken}" };
}

const RegistrationBody = z.object({ fcmToken: FcmToken });

async function register({ request, env, url, now }: Context): Promise<Handled> {
	const ip = request.headers.get("cf-connecting-ip") ?? "unknown";
	if (!(await env.REGISTRATION_LIMITER.limit({ key: ip })).success) return { reply: { kind: "rateLimited" } };
	const body = await parseJsonBody(request, RegistrationBody);
	if (body.kind !== "parsed") return { reply: body };
	const capability = newCapability();
	const secret = newRegistrationSecret();
	const rid = await ridOf(capability);
	await insertRegistration(env.DB, {
		rid,
		fcmToken: body.value.fcmToken,
		secretHash: await hashSecret(secret),
		createdAt: now,
	});
	return { rid, reply: { kind: "registered", rid, pushUrl: `${url.origin}/v1/push/${capability}`, secret } };
}

async function checkSecret(
	{ request, env }: Context,
	rid: Rid,
): Promise<{ kind: "authorized" } | Extract<Reply, { kind: "unauthorized" | "notFound" }>> {
	const bearer = /^Bearer\s+(\S+)$/i.exec(request.headers.get("authorization") ?? "")?.[1];
	const secret = RegistrationSecret.safeParse(bearer);
	if (!secret.success) return { kind: "unauthorized" };
	const registration = await findRegistration(env.DB, rid);
	if (registration === null) return { kind: "notFound" };
	return registration.secretHash === (await hashSecret(secret.data)) ? { kind: "authorized" } : { kind: "unauthorized" };
}

async function update(context: Context): Promise<Handled> {
	const rid = Rid.safeParse(context.params.rid);
	if (!rid.success) return { reply: { kind: "notFound" } };
	const access = await checkSecret(context, rid.data);
	if (access.kind !== "authorized") return { rid: rid.data, reply: access };
	const body = await parseJsonBody(context.request, RegistrationBody);
	if (body.kind !== "parsed") return { rid: rid.data, reply: body };
	await updateFcmToken(context.env.DB, { rid: rid.data, fcmToken: body.value.fcmToken, now: context.now });
	return { rid: rid.data, reply: { kind: "noContent" } };
}

async function remove(context: Context): Promise<Handled> {
	const rid = Rid.safeParse(context.params.rid);
	if (!rid.success) return { reply: { kind: "notFound" } };
	const access = await checkSecret(context, rid.data);
	if (access.kind !== "authorized") return { rid: rid.data, reply: access };
	await deleteRegistration(context.env.DB, rid.data);
	return { rid: rid.data, reply: { kind: "noContent" } };
}

function vapidLog(check: VapidCheck): VapidLog {
	if (check.kind !== "present") return check;
	const { scheme, key, claims } = check;
	return { kind: "present", scheme, key, ...claims };
}

async function push({ request, env, url, params, now }: Context): Promise<Handled> {
	const { headers } = request;
	const body = await readBounded(request, MAX_PUSH_BYTES);
	const vapid = parseVapid(headers);
	const capability = Capability.safeParse(params.capability);
	const rid = capability.success ? await ridOf(capability.data) : null;
	const facts: PushLog = {
		bodyBytes: body.kind === "body" ? body.bytes.byteLength : body.bytesRead,
		contentEncoding: headers.get("content-encoding"),
		ttl: headers.get("ttl"),
		urgency: headers.get("urgency"),
		topic: headers.get("topic"),
		vapid: vapidLog(vapid),
	};
	const handled = (reply: Reply, fcm?: FcmOutcome["kind"]): Handled => ({
		reply,
		...(rid === null ? {} : { rid }),
		push: fcm === undefined ? facts : { ...facts, fcm },
	});

	if (body.kind === "tooLarge") return handled({ kind: "tooLarge" });
	if (headers.get("content-encoding")?.trim().toLowerCase() !== "aes128gcm") {
		return handled({ kind: "badRequest", reason: "content-encoding must be aes128gcm" });
	}
	if (env.AMP_VAPID_PUBLIC_KEY !== "") {
		const verdict = await verifyVapid({
			check: vapid,
			pinnedKey: env.AMP_VAPID_PUBLIC_KEY,
			audience: url.origin,
			nowSeconds: Math.floor(now / 1000),
		});
		if (verdict.kind === "denied") return handled({ kind: "forbidden", reason: verdict.reason });
	}
	if (rid === null) return handled({ kind: "gone" });
	if (!(await env.PUSH_LIMITER.limit({ key: rid })).success) return handled({ kind: "rateLimited" });
	const registration = await findRegistration(env.DB, rid);
	if (registration === null) return handled({ kind: "gone" });

	const outcome = await sendToFcm({
		serviceAccountJson: env.FCM_SERVICE_ACCOUNT,
		message: buildMessage({
			token: registration.fcmToken,
			rid,
			body: body.bytes,
			ttl: headers.get("ttl"),
			topic: headers.get("topic"),
		}),
		now,
	});
	if (outcome.kind === "sent") await markPushed(env.DB, rid, now);
	if (outcome.kind === "gone") await deleteRegistration(env.DB, rid);
	return handled(outcome, outcome.kind);
}

const ROUTES: { name: Exclude<RouteName, "unmatched">; method: string; path: RegExp; handle: (context: Context) => Promise<Handled> }[] = [
	{ name: "health", method: "GET", path: /^\/health$/, handle: async () => ({ reply: { kind: "healthy" } }) },
	{ name: "register", method: "POST", path: /^\/v1\/registrations$/, handle: register },
	{ name: "update", method: "PUT", path: /^\/v1\/registrations\/(?<rid>[^/]+)$/, handle: update },
	{ name: "delete", method: "DELETE", path: /^\/v1\/registrations\/(?<rid>[^/]+)$/, handle: remove },
	{ name: "push", method: "POST", path: /^\/v1\/push\/(?<capability>[^/]+)$/, handle: push },
];

export default {
	async fetch(request: Request, env: RelayEnv): Promise<Response> {
		const started = Date.now();
		const url = new URL(request.url);
		let route: RouteName = "unmatched";
		let handled: Handled = { reply: { kind: "notFound" } };
		try {
			for (const candidate of ROUTES) {
				const match = candidate.method === request.method ? candidate.path.exec(url.pathname) : null;
				if (match === null) continue;
				route = candidate.name;
				handled = await candidate.handle({ request, env, url, params: match.groups ?? {}, now: started });
				break;
			}
		} catch (error) {
			handled = { reply: { kind: "failed", reason: error instanceof Error ? error.message : String(error) } };
		}
		const response = toResponse(handled.reply);
		log({ route, method: request.method, status: response.status, started, handled });
		return response;
	},
} satisfies ExportedHandler<RelayEnv>;
