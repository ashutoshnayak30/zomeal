import test from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import { validateAudio, validateDraft } from './domain.mjs';
import { createVoiceService } from './server.mjs';

const audio = { language: 'hi-IN', mode: 'PURCHASE', audio: Buffer.alloc(32000).toString('base64') };
test('recipe mode accepts Odia audio and preserves batch ingredient quantities without inventing costs', () => {
  assert.equal(validateAudio({...audio, mode:'RECIPE', language:'or-IN'}).mode,'RECIPE');
  const result=validateDraft({items:[{item:'Chicken', category:'Meat & eggs',quantity:1,unit:'kg',unit_price_rupees:null,total_rupees:null}]},'RECIPE');
  assert.equal(result.items[0].quantity,1);
  assert.equal(result.items[0].amount_paise,null);
  assert.equal(result.saved,false);
});
const row = { item: 'Rice', category: 'Grains', quantity: 5, unit: 'kg', unit_price_rupees: 60, total_rupees: null };
const env = { VOICE_ENABLED: 'true', SUPABASE_URL: 'https://example.supabase.co', SUPABASE_ANON_KEY: 'public-test', GOOGLE_CLOUD_PROJECT: 'test-project', VOICE_MODEL: 'test-model' };
function request(body = audio, authorization = 'Bearer session') {
  return Object.assign(Readable.from([Buffer.from(JSON.stringify(body))]), { method: 'POST', url: '/v1/draft', headers: { authorization, 'content-type': 'application/json' } });
}
function fake(options = {}) {
  const calls = [];
  let vertexCalls = 0;
  const fetcher = async (url, init) => {
    calls.push({ url, init });
    let status = 200, body;
    if (url.endsWith('/auth/v1/user')) { status = options.authStatus || 200; body = options.user ?? { id: 'owner', is_anonymous: false }; }
    else if (url.endsWith('/provider_voice_reserve')) body = options.gate ?? { allowed: true, request_id: 'server-id', remaining_requests: 19 };
    else if (url.includes('metadata.google.internal')) body = { access_token: 'metadata-token' };
    else if (url.includes('speech.googleapis.com')) { status = options.speechStatus || 200; body = { results: [{ alternatives: [{ transcript: options.transcript ?? 'पांच किलो चावल तीन सौ रुपये' }] }] }; }
    else if (url.includes('aiplatform.googleapis.com')) {
      const translation=JSON.parse(init.body).generationConfig.responseSchema.properties.names;
      body = { candidates: [{ finishReason: options.finishReason || 'STOP', content: { parts: [{ text: JSON.stringify(translation ? (options.translation ?? {transcript:'ପାଞ୍ଚ କିଲୋ ଚାଉଳ ତିନି ଶହ ଟଙ୍କା',names:['ଚାଉଳ']}) : (options.draft ?? { items: [row] })) }] } }] };
    }
    else throw new Error('Unexpected network target');
    if (url.includes('aiplatform.googleapis.com')) status = options.vertexStatuses?.[vertexCalls++] ?? options.vertexStatus ?? 200;
    return new Response(JSON.stringify(body), { status, headers: options.retryAfter ? { 'Retry-After': options.retryAfter } : {} });
  };
  return { calls, fetcher };
}
test('accepts automatic language, Hindi and Odia; derives duration from PCM bytes', () => {
  for (const language of ['auto', 'hi-IN', 'or-IN']) assert.equal(validateAudio({ ...audio, language }).seconds, 1);
});
test('automatic mode uses one Singapore speech request with automatic language recognition', async () => {
  const f = fake(); await createVoiceService(env, f.fetcher)(request({ ...audio, language: 'auto' }));
  assert.deepEqual(JSON.parse(f.calls[3].init.body).config.languageCodes, ['auto']);
  assert.equal(f.calls.filter(c => c.url.includes('speech.googleapis.com')).length, 1);
});

test('explicit Indian language choices are preserved in speech requests', async () => {
  for (const language of ['or-IN', 'hi-IN', 'en-IN', 'as-IN', 'bn-IN', 'gu-IN', 'kn-IN', 'ml-IN', 'mr-IN', 'ne-NP', 'pa-Guru-IN', 'sd-IN', 'ta-IN', 'te-IN', 'ur-PK']) {
    assert.equal(validateAudio({ ...audio, language }).language, language);
    const f = fake(); await createVoiceService(env, f.fetcher)(request({ ...audio, language }));
    assert.deepEqual(JSON.parse(f.calls[3].init.body).config.languageCodes, [language]);
  }
  for (const language of ['', 'sa-IN', 'xx']) assert.throws(() => validateAudio({ ...audio, language }));
});
test('rejects unsupported language, mode, malformed base64 and oversized audio', () => {
  for (const change of [{ language: 'xx' }, { mode: 'REFUND' }, { audio: '!!!!' }, { audio: Buffer.alloc(1920002).toString('base64') }]) assert.throws(() => validateAudio({ ...audio, ...change }));
});

test('Odia choice translates Bengali recognition without changing quantities or costs', async () => {
  const f=fake({transcript:'দুই কিলো চিকেন'});
  const result=await createVoiceService(env,f.fetcher)(request({...audio,language:'or-IN'}));
  assert.equal(result.body.items[0].item,'ଚାଉଳ');
  assert.equal(result.body.items[0].quantity,5);
  assert.equal(result.body.items[0].amount_paise,30000);
  assert.equal(result.body.translated_to,'or-IN');
  assert.equal(result.body.saved,false);
  assert.equal(f.calls.filter(c=>c.url.includes('speech.googleapis.com')).length,1);
  assert.equal(f.calls.filter(c=>c.url.includes('aiplatform.googleapis.com')).length,2);
  assert.ok(f.calls.filter(c=>c.url.includes('aiplatform.googleapis.com')).every(c=>c.url.includes('asia-south1')));
});
test('Odia translation rejects Bengali output, missing names and malformed names',async()=>{
 for(const translation of [{transcript:'বাংলা',names:['চাল']},{transcript:'ଚାଉଳ',names:[]},{transcript:'ଚାଉଳ',names:[null]}]){
  const f=fake({translation});await assert.rejects(createVoiceService(env,f.fetcher)(request({...audio,language:'or-IN'})),{status:502});
 }
});
test('Odia script and intentional Bengali choice remain supported', async () => {
  for(const [language,transcript] of [['or-IN','ଦୁଇ କିଲୋ ଚାଉଳ'],['bn-IN','দুই কিলো চাল']]){
    const f=fake({transcript}); const result=await createVoiceService(env,f.fetcher)(request({...audio,language}));
    assert.equal(result.status,200);
  }
});
test('computes quantity × price, but never marks a draft saved', () => {
  const result = validateDraft({ items: [row] }, 'PURCHASE');
  assert.equal(result.items[0].amount_paise, 30000); assert.equal(result.saved, false); assert.equal(result.requires_review, true);
});
test('unknown stock purchase cost remains missing, not zero', () => {
  const result = validateDraft({ items: [{ ...row, unit_price_rupees: null }] }, 'CLOSING_STOCK');
  assert.equal(result.items[0].amount_paise, null); assert.ok(result.items[0].issues.includes('purchase-cost valuation'));
});
test('flags price conflicts, zero purchases and non-ingredient stock', () => {
  assert.ok(validateDraft({ items: [{ ...row, total_rupees: 400 }] }, 'PURCHASE').items[0].issues.length);
  assert.ok(validateDraft({ items: [{ ...row, quantity: 0 }] }, 'PURCHASE').items[0].issues.length);
  assert.ok(validateDraft({ items: [{ ...row, category: 'Gas & fuel' }] }, 'OPENING_STOCK').items[0].issues.length);
});
test('accepts explicitly zero remaining stock', () => {
  const result = validateDraft({ items: [{ ...row, quantity: 0 }] }, 'CLOSING_STOCK');
  assert.equal(result.items[0].amount_paise, 0); assert.deepEqual(result.items[0].issues, []);
});
test('rejects invalid model amounts, categories, units and row counts', () => {
  for (const change of [{ quantity: -1 }, { quantity: '5' }, { category: 'Wallet' }, { unit: 'truck' }, { total_rupees: Infinity }]) assert.throws(() => validateDraft({ items: [{ ...row, ...change }] }, 'PURCHASE'));
  assert.throws(() => validateDraft({ items: Array(31).fill(row) }, 'PURCHASE'));
});
test('missing session rejected before all network requests', async () => {
  const f = fake(); await assert.rejects(createVoiceService(env, f.fetcher)(request(audio, '')), { status: 401 }); assert.equal(f.calls.length, 0);
});
test('expired and anonymous sessions cannot reserve quota or call Google', async () => {
  for (const option of [{ authStatus: 401 }, { user: { id: 'anon', is_anonymous: true } }]) {
    const f = fake(option); await assert.rejects(createVoiceService(env, f.fetcher)(request()), { status: 401 }); assert.equal(f.calls.length, 1);
  }
});
test('customer, non-pilot and disabled membership denials stop before Google', async () => {
  const f = fake({ gate: { allowed: false, reason: 'ACCESS' } });
  await assert.rejects(createVoiceService(env, f.fetcher)(request()), { status: 403 }); assert.equal(f.calls.length, 2);
});
test('quota exhaustion stops before Google', async () => {
  const f = fake({ gate: { allowed: false, reason: 'LIMIT' } });
  await assert.rejects(createVoiceService(env, f.fetcher)(request()), { status: 429 }); assert.equal(f.calls.length, 2);
});
test('kill switch defaults off', async () => {
  const f = fake(); await assert.rejects(createVoiceService({ ...env, VOICE_ENABLED: undefined }, f.fetcher)(request()), { status: 503 }); assert.equal(f.calls.length, 0);
});
test('permitted request returns review-only rows, never calls save or wallet APIs', async () => {
  const f = fake(); const result = await createVoiceService(env, f.fetcher)(request());
  assert.equal(result.status, 200); assert.equal(result.body.saved, false); assert.equal(f.calls.length, 5);
  assert.equal(JSON.parse(f.calls[1].init.body).audio_seconds, 1);
  assert.equal(JSON.parse(f.calls[3].init.body).config.model, 'chirp_2');
  assert.ok(f.calls[3].url.startsWith('https://asia-southeast1-speech.googleapis.com/'));
  assert.ok(f.calls[3].url.includes('/locations/asia-southeast1/'));
  assert.ok(f.calls[4].url.startsWith('https://asia-south1-aiplatform.googleapis.com/'));
  assert.ok(f.calls[4].url.includes('/locations/asia-south1/'));
  assert.ok(!f.calls.some(c => /save|wallet/.test(c.url)));
});
test('replaying client IDs still makes different server quota reservations', async () => {
  const f = fake(); const handle = createVoiceService(env, f.fetcher);
  await handle(request({ ...audio, request_id: 'same' })); await handle(request({ ...audio, request_id: 'same' }));
  assert.notEqual(JSON.parse(f.calls[1].init.body).request_id, JSON.parse(f.calls[6].init.body).request_id);
});

test('Vertex schema uses live-verified simple constraints and permits unknown units as null', async () => {
  const f = fake(); await createVoiceService(env, f.fetcher)(request());
  const body = JSON.parse(f.calls[4].init.body);
  const schema = body.generationConfig.responseSchema;
  function visit(value) {
    if (!value || typeof value !== 'object') return;
      assert.equal(value.enum, undefined);
      assert.equal(value.maxItems, undefined);
    Object.values(value).forEach(visit);
  }
  visit(schema);
  assert.equal(schema.properties.items.items.properties.unit.nullable, true);
  assert.match(body.systemInstruction.parts[0].text, /unknown unit must be null/);
    assert.match(body.systemInstruction.parts[0].text, /Category must be one of:/);
    assert.match(body.systemInstruction.parts[0].text, /Unit must be kg, g, litre, ml, piece, packet, bag, cylinder or null/);
});

test('null unit stays unknown and blocks automatic unit-price calculation', () => {
  for (const mode of ['PURCHASE', 'OPENING_STOCK', 'CLOSING_STOCK']) {
    const result = validateDraft({ items: [{ ...row, unit: null }] }, mode);
    assert.equal(result.items[0].unit, '');
    assert.equal(result.items[0].amount_paise, null);
    assert.ok(result.items[0].issues.includes('quantity / unit'));
    assert.equal(result.saved, false);
  }
});

test('persistent Vertex 429 stops after one retry with actionable error', async () => {
  const f = fake({ vertexStatus: 429 });
  const delays = [];
  await assert.rejects(createVoiceService(env, f.fetcher, { sleep: async ms => delays.push(ms) })(request()), error => error.status === 503 && /capacity or quota/.test(error.message));
  assert.equal(f.calls.length, 6);
  assert.equal(delays.length, 1);
  assert.ok(delays[0] >= 1500 && delays[0] < 2000);
});

test('429 retry reuses India transcript and returns review only without repeating speech or quota', async () => {
  const f = fake({ vertexStatuses: [429, 200] });
  const result = await createVoiceService(env, f.fetcher, { sleep: async () => {} })(request());
  assert.equal(result.body.saved, false);
  assert.equal(result.body.requires_review, true);
  assert.equal(f.calls.length, 6);
  assert.equal(f.calls[4].url, f.calls[5].url);
  assert.equal(f.calls[4].init.body, f.calls[5].init.body);
  assert.ok(f.calls[5].url.includes('asia-south1-aiplatform'));
});

test('non-429 extraction errors are not retried', async () => {
  for (const vertexStatus of [400, 401, 403, 500, 503]) {
    const f = fake({ vertexStatus });
    await assert.rejects(createVoiceService(env, f.fetcher, { sleep: async () => assert.fail('unexpected retry') })(request()));
    assert.equal(f.calls.length, 5);
  }
});

test('long Retry-After skips retry and respects upstream cooldown', async () => {
  const f = fake({ vertexStatus: 429, retryAfter: '60' });
  await assert.rejects(createVoiceService(env, f.fetcher, { sleep: async () => assert.fail('unexpected retry') })(request()), { status: 503 });
  assert.equal(f.calls.length, 5);
});

test('exhausted extraction time budget prevents retry', async () => {
  const f = fake({ vertexStatus: 429 });
  let ticks = 0;
  await assert.rejects(createVoiceService(env, f.fetcher, { now: () => ticks++ < 2 ? 0 : 44500, sleep: async () => assert.fail('unexpected retry') })(request()), { status: 503 });
  assert.equal(f.calls.length, 5);
});
test('speech failure and empty recording do not call extraction', async () => {
  for (const [option, status] of [[{ speechStatus: 403 }, 502], [{ transcript: '' }, 422]]) {
    const f = fake(option); await assert.rejects(createVoiceService(env, f.fetcher)(request()), { status }); assert.equal(f.calls.length, 4);
  }
});
test('truncated model output is rejected', async () => {
  const f = fake({ finishReason: 'MAX_TOKENS' }); await assert.rejects(createVoiceService(env, f.fetcher)(request()), { status: 502 });
});
