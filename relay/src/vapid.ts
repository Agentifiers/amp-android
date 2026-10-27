import { z } from "zod";
import { decodeBase64url } from "./base64url";

const JwtHeader = z.object({ alg: z.string() });

const VapidClaims = z.object({ aud: z.string(), exp: z.number(), sub: z.string().optional() });
export type VapidClaims = z.infer<typeof VapidClaims>;

export type VapidScheme = "vapid" | "webpush";

type SignedJwt = { signingInput: string; signature: Uint8Array };

export type VapidCheck =
	| { kind: "absent" }
	| { kind: "present"; scheme: VapidScheme; key: string; claims: VapidClaims; jwt: SignedJwt }
	| { kind: "invalid"; reason: string };

type VapidVerdict = { kind: "allowed" } | { kind: "denied"; reason: string };

type Presented = { scheme: VapidScheme; token: string | undefined; key: string | undefined };

function presentedCredentials(authorization: string, cryptoKey: string | null): Presented | null {
	const match = /^(\S+)\s+(.*)$/s.exec(authorization.trim());
	if (match === null) return null;
	const [, scheme = "", rest = ""] = match;
	switch (scheme.toLowerCase()) {
		case "vapid": {
			const params = new Map(
				rest.split(",").map((param) => {
					const [name = "", ...value] = param.trim().split("=");
					return [name.toLowerCase(), value.join("=")];
				}),
			);
			return { scheme: "vapid", token: params.get("t"), key: params.get("k") };
		}
		case "webpush": {
			const key = cryptoKey
				?.split(/[;,]/)
				.map((param) => param.trim())
				.find((param) => param.startsWith("p256ecdsa="))
				?.slice("p256ecdsa=".length);
			return { scheme: "webpush", token: rest.trim(), key };
		}
		default:
			return null;
	}
}

function decodeJsonSegment(segment: string): unknown {
	const bytes = decodeBase64url(segment);
	if (bytes === null) return undefined;
	try {
		return JSON.parse(new TextDecoder().decode(bytes));
	} catch {
		return undefined;
	}
}

export function parseVapid(headers: Headers): VapidCheck {
	const authorization = headers.get("authorization");
	if (authorization === null) return { kind: "absent" };
	const presented = presentedCredentials(authorization, headers.get("crypto-key"));
	if (presented === null) return { kind: "invalid", reason: "unsupported authorization scheme" };
	if (presented.token === undefined || presented.token === "") return { kind: "invalid", reason: "missing jwt" };
	if (presented.key === undefined || presented.key === "") return { kind: "invalid", reason: "missing key" };

	const segments = presented.token.split(".");
	const [headerSegment, claimsSegment, signatureSegment] = segments;
	if (segments.length !== 3 || headerSegment === undefined || claimsSegment === undefined || signatureSegment === undefined) {
		return { kind: "invalid", reason: "malformed jwt" };
	}
	const header = JwtHeader.safeParse(decodeJsonSegment(headerSegment));
	if (!header.success) return { kind: "invalid", reason: "malformed jwt header" };
	if (header.data.alg !== "ES256") return { kind: "invalid", reason: `unsupported alg ${header.data.alg}` };
	const claims = VapidClaims.safeParse(decodeJsonSegment(claimsSegment));
	if (!claims.success) return { kind: "invalid", reason: "malformed jwt claims" };
	const signature = decodeBase64url(signatureSegment);
	if (signature === null) return { kind: "invalid", reason: "malformed jwt signature" };

	return {
		kind: "present",
		scheme: presented.scheme,
		key: presented.key.replace(/=+$/, ""),
		claims: claims.data,
		jwt: { signingInput: `${headerSegment}.${claimsSegment}`, signature },
	};
}

export async function verifyVapid(request: {
	check: VapidCheck;
	pinnedKey: string;
	audience: string;
	nowSeconds: number;
}): Promise<VapidVerdict> {
	const { check, pinnedKey, audience, nowSeconds } = request;
	switch (check.kind) {
		case "absent":
			return { kind: "denied", reason: "vapid missing" };
		case "invalid":
			return { kind: "denied", reason: `vapid invalid: ${check.reason}` };
		case "present":
			break;
		default: {
			const _exhaustive: never = check;
			return _exhaustive;
		}
	}
	if (check.key !== pinnedKey) return { kind: "denied", reason: "vapid key mismatch" };
	const keyBytes = decodeBase64url(pinnedKey);
	if (keyBytes === null) return { kind: "denied", reason: "pinned vapid key is not base64url" };
	let publicKey: CryptoKey;
	try {
		publicKey = await crypto.subtle.importKey("raw", keyBytes, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
	} catch {
		return { kind: "denied", reason: "pinned vapid key is not a P-256 point" };
	}
	const signed = await crypto.subtle.verify(
		{ name: "ECDSA", hash: "SHA-256" },
		publicKey,
		check.jwt.signature,
		new TextEncoder().encode(check.jwt.signingInput),
	);
	if (!signed) return { kind: "denied", reason: "vapid signature" };
	if (check.claims.aud !== audience) return { kind: "denied", reason: "vapid aud" };
	if (check.claims.exp <= nowSeconds) return { kind: "denied", reason: "vapid expired" };
	return { kind: "allowed" };
}
