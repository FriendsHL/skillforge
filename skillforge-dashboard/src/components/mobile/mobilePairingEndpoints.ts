export function buildMobilePairingEndpoints(origin: string, configured: string | undefined): string[] {
  const additional = (configured ?? '')
    .split(',')
    .map((endpoint) => endpoint.trim())
    .filter(Boolean);
  return Array.from(new Set([origin, ...additional]));
}
