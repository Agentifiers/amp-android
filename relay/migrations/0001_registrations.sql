CREATE TABLE registrations (
	rid TEXT PRIMARY KEY,
	fcm_token TEXT NOT NULL,
	secret_hash TEXT NOT NULL,
	created_at INTEGER NOT NULL,
	updated_at INTEGER NOT NULL,
	last_push_at INTEGER
);
