import { z } from "zod";
import { encodeBase64url } from "./base64url";

const base64url32 = z.string().regex(/^[A-Za-z0-9_-]{43}$/);

export const Capability = base64url32.brand<"Capability">();
export type Capability = z.infer<typeof Capability>;

export const Rid = base64url32.brand<"Rid">();
export type Rid = z.infer<typeof Rid>;

export const RegistrationSecret = base64url32.brand<"RegistrationSecret">();
export type RegistrationSecret = z.infer<typeof RegistrationSecret>;

export const SecretHash = base64url32.brand<"SecretHash">();
export type SecretHash = z.infer<typeof SecretHash>;

export const FcmToken = z.string().min(1).max(4096).brand<"FcmToken">();
export type FcmToken = z.infer<typeof FcmToken>;

async function sha256Base64url(text: string): Promise<string> {
	const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
	return encodeBase64url(new Uint8Array(digest));
}

export function newCapability(): Capability {
	return Capability.parse(encodeBase64url(crypto.getRandomValues(new Uint8Array(32))));
}

export function newRegistrationSecret(): RegistrationSecret {
	return RegistrationSecret.parse(encodeBase64url(crypto.getRandomValues(new Uint8Array(32))));
}

export async function ridOf(capability: Capability): Promise<Rid> {
	return Rid.parse(await sha256Base64url(capability));
}

export async function hashSecret(secret: RegistrationSecret): Promise<SecretHash> {
	return SecretHash.parse(await sha256Base64url(secret));
}
