// Cloud Shell diagnostic: read configuration and make at most TWO billable synthetic
// model request as the signed-in owner. No real audio, database calls or writes.
import { execFileSync } from 'node:child_process';
import { Readable } from 'node:stream';
import { createVoiceService } from './server.mjs';
const project = 'zomeal-kitchen-ai';
const gcloud = args => execFileSync('gcloud', args, { encoding: 'utf8' }).trim();
const service = JSON.parse(gcloud(['run', 'services', 'describe', 'zomeal-kitchen-voice', '--project=' + project, '--region=asia-southeast1', '--format=json']));
const settings = Object.fromEntries(service.spec.template.spec.containers[0].env.map(e => [e.name, e.value || '']));
const identity = service.spec.template.spec.serviceAccountName;
console.log('Runtime identity:', identity);
console.log('Configured model:', settings.VOICE_MODEL);
console.log('At most two synthetic model attempts: one bounded retry only on HTTP 429.');
const policy = JSON.parse(gcloud(['projects', 'get-iam-policy', project, '--format=json']));
console.log('Runtime project roles:', policy.bindings.filter(b => b.members?.includes('serviceAccount:' + identity)).map(b => ({ role: b.role, conditional: !!b.condition })));
try {
  const role = JSON.parse(gcloud(['iam', 'roles', 'describe', 'zomealVoiceInference', '--project=' + project, '--format=json']));
  console.log('Inference role permissions:', role.includedPermissions);
} catch { console.log('Could not inspect custom inference role.'); }
const ownerToken = gcloud(['auth', 'print-access-token']);
const mock = data => new Response(JSON.stringify(data), { status: 200 });
const fetcher = async (url, init) => {
  // All auth/quota/speech steps below are LOCAL test doubles, never sent anywhere.
  if (url === 'https://example.supabase.co/auth/v1/user') return mock({ id: 'synthetic-owner', is_anonymous: false });
  if (url === 'https://example.supabase.co/rest/v1/rpc/provider_voice_reserve') return mock({ allowed: true });
  if (url.includes('metadata.google.internal')) return mock({ access_token: ownerToken });
  if (url.startsWith('https://asia-southeast1-speech.googleapis.com/')) return mock({ results: [{ alternatives: [{ transcript: 'दो किलो टमाटर कुल चालीस रुपये' }] }] });
  const expected = `https://asia-south1-aiplatform.googleapis.com/v1/projects/${project}/locations/asia-south1/publishers/google/models/`;
  if (!url.startsWith(expected)) throw new Error('Unexpected endpoint. Stopping.');
  console.log('Testing exact backend extraction schema in India with synthetic text.');
  const response = await fetch(url, init);
  const data = await response.clone().json();
  console.log('Vertex HTTP:', response.status);
  if (!response.ok) console.log('Synthetic-request error:', JSON.stringify(data.error));
  return response;
};
const request = Object.assign(Readable.from([Buffer.from(JSON.stringify({ language: 'hi-IN', mode: 'PURCHASE', audio: Buffer.alloc(32000).toString('base64') }))]), {
  method: 'POST', url: '/v1/draft', headers: { authorization: 'Bearer synthetic-session', 'content-type': 'application/json' }
});
try {
  const result = await createVoiceService({ VOICE_ENABLED: 'true', SUPABASE_URL: 'https://example.supabase.co', SUPABASE_ANON_KEY: 'synthetic-public', GOOGLE_CLOUD_PROJECT: project, VOICE_MODEL: settings.VOICE_MODEL }, fetcher)(request);
  console.log('Extraction validation:', result.status, 'saved:', result.body.saved);
} catch (error) { console.log('Diagnostic result:', error.message); process.exitCode = 1; }
console.log('Owner-token test only: runtime identity access is NOT proven by a successful result.');
