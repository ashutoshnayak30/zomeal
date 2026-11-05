// Cloud Shell only. At most three billable synthetic requests in India.
// No real speech, Supabase calls, deployment changes, or credential output.
import { execFileSync } from 'node:child_process';
import { Readable } from 'node:stream';
import { createVoiceService } from './server.mjs';
const project = 'zomeal-kitchen-ai';
const cli = args => execFileSync('gcloud', args, { encoding: 'utf8' }).trim();
const service = JSON.parse(cli(['run', 'services', 'describe', 'zomeal-kitchen-voice', '--project=' + project, '--region=asia-southeast1', '--format=json']));
const model = service.spec.template.spec.containers[0].env.find(e => e.name === 'VOICE_MODEL')?.value;
if (!model) throw new Error('Model is not configured.');
let captured;
const mock = body => new Response(JSON.stringify(body));
const request = Object.assign(Readable.from([Buffer.from(JSON.stringify({ language: 'hi-IN', mode: 'PURCHASE', audio: Buffer.alloc(32000).toString('base64') }))]), {
  method: 'POST', url: '/v1/draft', headers: { authorization: 'Bearer synthetic-session', 'content-type': 'application/json' }
});
try {
  await createVoiceService({ VOICE_ENABLED: 'true', SUPABASE_URL: 'https://example.supabase.co', SUPABASE_ANON_KEY: 'synthetic', GOOGLE_CLOUD_PROJECT: project, VOICE_MODEL: model }, async (url, init) => {
    if (url === 'https://example.supabase.co/auth/v1/user') return mock({ id: 'synthetic', is_anonymous: false });
    if (url === 'https://example.supabase.co/rest/v1/rpc/provider_voice_reserve') return mock({ allowed: true });
    if (url.includes('metadata.google.internal')) return mock({ access_token: 'synthetic' });
    if (url.startsWith('https://asia-southeast1-speech.googleapis.com/')) return mock({ results: [{ alternatives: [{ transcript: 'दो किलो टमाटर कुल चालीस रुपये' }] }] });
    if (!url.startsWith(`https://asia-south1-aiplatform.googleapis.com/v1/projects/${project}/locations/asia-south1/`)) throw new Error('Unexpected endpoint');
    captured = { url, body: JSON.parse(init.body) };
    return new Response('{}', { status: 400 });
  })(request);
} catch (error) { if (!captured) throw error; }
const minimal = { contents: [{ role: 'user', parts: [{ text: 'Synthetic test: 2 kg tomatoes cost 40 rupees total. Return item, quantity and total_rupees.' }] }], generationConfig: {
  maxOutputTokens: 512, responseMimeType: 'application/json', responseSchema: { type: 'OBJECT', required: ['item', 'quantity', 'total_rupees'], properties: { item: { type: 'STRING' }, quantity: { type: 'NUMBER' }, total_rupees: { type: 'NUMBER' } } }
} };
const simplified = structuredClone(captured.body);
const rows = simplified.generationConfig.responseSchema.properties.items;
delete rows.maxItems;
for (const field of Object.values(rows.items.properties)) delete field.enum;
// Retain nullability and required fields; backend validation remains unchanged.
simplified.systemInstruction.parts[0].text += ' Category must be one of: ' + captured.body.generationConfig.responseSchema.properties.items.items.properties.category.enum.join(', ') + '. Unit must be kg, g, litre, ml, piece, packet, bag, cylinder or null.';
const token = cli(['auth', 'print-access-token']);
console.log('Model:', model, '| Region: asia-south1 | Synthetic tests only');
for (const [name, body] of [['minimal baseline', minimal], ['exact backend schema', captured.body], ['simplified constraints', simplified]]) {
  const response = await fetch(captured.url, { method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal: AbortSignal.timeout(60000) });
  const data = await response.json();
  console.log(name + ': HTTP ' + response.status);
  if (!response.ok) console.log(JSON.stringify(data.error));
  else {
    console.log('Finish:', data.candidates?.[0]?.finishReason);
    console.log('Synthetic output:', data.candidates?.[0]?.content?.parts?.filter(p => !p.thought).map(p => p.text || '').join(''));
  }
  if (response.status === 429 || response.status === 401 || response.status === 403) { console.log('Stopping; no automatic retries.'); break; }
}
console.log('Owner-token diagnostics only. Runtime identity and end-to-end app still need verification. Nothing saved.');
