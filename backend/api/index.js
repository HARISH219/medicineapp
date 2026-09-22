// Vercel serverless entry point. All requests are rewritten here (see vercel.json).
// Vercel may hand the function a path prefixed with /api or /api/index depending on
// routing; we normalize req.url back to the real API path before Express handles it,
// so routes like /health and /v1/events resolve correctly.
import app from "../app.js";

export default function handler(req, res) {
  let url = req.url || "/";
  url = url.replace(/^\/api\/index/, "").replace(/^\/api(?=\/|$)/, "");
  if (url === "" ) url = "/";
  req.url = url;
  return app(req, res);
}
