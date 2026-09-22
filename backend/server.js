// Standalone server entry point (local dev / Railway / Fly / any always-on host).
// Vercel uses api/index.js instead, which imports the same app.

import app from "./app.js";

const PORT = process.env.PORT || 8080;
app.listen(PORT, () => console.log(`TB MedTrack backend on :${PORT}`));
