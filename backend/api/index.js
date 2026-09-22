// Vercel serverless entry. vercel.json rewrites every path here. Vercel preserves the
// original request path in req.url (e.g. "/health", "/v1/events"), so Express routes it
// directly. We only strip a possible "/api" prefix as a safety net.
import app from "../app.js";

export default function handler(req, res) {
  if (req.url && req.url.startsWith("/api/")) req.url = req.url.slice(4) || "/";
  else if (req.url === "/api") req.url = "/";
  return app(req, res);
}
