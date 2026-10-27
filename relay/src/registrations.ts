import { z } from "zod";
import { FcmToken, Rid, SecretHash } from "./ids";

const RegistrationRow = z
	.object({
		rid: Rid,
		fcm_token: FcmToken,
		secret_hash: SecretHash,
		created_at: z.number(),
		updated_at: z.number(),
		last_push_at: z.number().nullable(),
	})
	.transform((row) => ({
		rid: row.rid,
		fcmToken: row.fcm_token,
		secretHash: row.secret_hash,
		createdAt: row.created_at,
		updatedAt: row.updated_at,
		lastPushAt: row.last_push_at,
	}));

export type Registration = z.infer<typeof RegistrationRow>;

export async function findRegistration(db: D1Database, rid: Rid): Promise<Registration | null> {
	const row = await db.prepare("SELECT * FROM registrations WHERE rid = ?").bind(rid).first();
	return row === null ? null : RegistrationRow.parse(row);
}

export async function insertRegistration(
	db: D1Database,
	registration: Pick<Registration, "rid" | "fcmToken" | "secretHash" | "createdAt">,
): Promise<void> {
	await db
		.prepare(
			"INSERT INTO registrations (rid, fcm_token, secret_hash, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
		)
		.bind(
			registration.rid,
			registration.fcmToken,
			registration.secretHash,
			registration.createdAt,
			registration.createdAt,
		)
		.run();
}

export async function updateFcmToken(
	db: D1Database,
	update: { rid: Rid; fcmToken: FcmToken; now: number },
): Promise<void> {
	await db
		.prepare("UPDATE registrations SET fcm_token = ?, updated_at = ? WHERE rid = ?")
		.bind(update.fcmToken, update.now, update.rid)
		.run();
}

export async function markPushed(db: D1Database, rid: Rid, now: number): Promise<void> {
	await db.prepare("UPDATE registrations SET last_push_at = ? WHERE rid = ?").bind(now, rid).run();
}

export async function deleteRegistration(db: D1Database, rid: Rid): Promise<void> {
	await db.prepare("DELETE FROM registrations WHERE rid = ?").bind(rid).run();
}
