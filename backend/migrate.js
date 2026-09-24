// Runs migrations/*.sql against the Turso database in order.
// Usage: TURSO_DATABASE_URL=... TURSO_AUTH_TOKEN=... node migrate.js
import { createClient } from "@libsql/client";
import { readFileSync, readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const { TURSO_DATABASE_URL, TURSO_AUTH_TOKEN } = process.env;
if (!TURSO_DATABASE_URL || !TURSO_AUTH_TOKEN) {
  console.error("Set TURSO_DATABASE_URL and TURSO_AUTH_TOKEN");
  process.exit(1);
}

const db = createClient({ url: TURSO_DATABASE_URL, authToken: TURSO_AUTH_TOKEN });
const here = dirname(fileURLToPath(import.meta.url));
const dir = join(here, "..", "migrations");

const files = readdirSync(dir).filter((f) => f.endsWith(".sql")).sort();
for (const f of files) {
  const sql = readFileSync(join(dir, f), "utf8");
  // Split on ';' at statement ends; libSQL executes one statement per call.
  const statements = sql.split(/;\s*$/m).map((s) => s.trim()).filter(Boolean);
  console.log(`Applying ${f} (${statements.length} statements)`);
  for (const stmt of statements) {
    try {
      await db.execute(stmt);
    } catch (e) {
      // Tolerate re-runs: "duplicate column name" (ADD COLUMN) and "already exists" (CREATE)
      // so migrations are safely idempotent without a tracking table.
      const msg = String(e && e.message).toLowerCase();
      if (msg.includes("duplicate column") || msg.includes("already exists")) {
        console.log(`  (skip, already applied) ${stmt.slice(0, 60)}...`);
      } else {
        throw e;
      }
    }
  }
}
console.log("Migrations complete.");
