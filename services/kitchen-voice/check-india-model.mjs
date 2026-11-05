// Run in the owner's authenticated Cloud Shell. One small, billable synthetic
// inference. No real provider data, no persistent credentials, no global fallback.
import { execFileSync } from 'node:child_process';
const project = 'zomeal-kitchen-ai';
const model = 'gemini-3.5-flash-lite';
const token = execFileSync('gcloud', ['auth', 'print-access-token'], { encoding: 'utf8' }).trim();
const response = await fetch(`https://asia-south1-aiplatform.googleapis.com/v1/projects/${project}/locations/asia-south1/publishers/google/models/${model}:generateContent`, {
  method: 'POST',
  headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
  signal: AbortSignal.timeout(60000),
  body: JSON.stringify({
    contents: [{ role: 'user', parts: [{ text: 'Test only: 2 kg tomatoes cost 40 rupees total. Return item, quantity and total_rupees as JSON.' }] }],
    generationConfig: { maxOutputTokens: 512, responseMimeType: 'application/json', responseSchema: {
      type: 'OBJECT', required: ['item', 'quantity', 'total_rupees'], properties: {
        item: { type: 'STRING' }, quantity: { type: 'NUMBER' }, total_rupees: { type: 'NUMBER' }
      }
    } }
  })
});
const result = await response.json();
console.log(`India model check: HTTP ${response.status}; model=${model}; region=asia-south1`);
if (!response.ok) {
  console.log(result.error?.message || 'Model unavailable. Do not deploy or switch regions automatically.');
  process.exitCode = 1;
} else {
  const candidate = result.candidates?.[0];
  console.log('Finish reason:', candidate?.finishReason);
  console.log('Synthetic result:', candidate?.content?.parts?.filter(p => p.text && !p.thought).map(p => p.text).join(''));
  console.log('This verifies owner access only. Runtime service identity and speech still need live checks.');
}
