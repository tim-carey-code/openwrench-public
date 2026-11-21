import assert from 'node:assert/strict';
import test from 'node:test';
import { analyze, DailyQuota } from './relay.mjs';

const env = {
  DEEPSEEK_API_KEY: 'fictional-provider-key',
  DAILY_QUOTA: { idFromName: name => name, get: () => ({ fetch: async () => new Response(null, { status: 204 }) }) },
  ANALYZE_LIMIT: { limit: async () => ({ success: true }) },
};

function request(evidence = 'synthetic report') {
  return new Request('https://relay.example/analyze', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ evidence }),
  });
}

test('configuration, size, and rate limits prevent upstream calls', async () => {
  const unexpected = async () => assert.fail('unexpected provider call');
  assert.equal((await analyze(request(), {}, unexpected)).status, 503);
  assert.equal((await analyze(request(''), env, unexpected)).status, 400);
  assert.equal((await analyze(request('x'.repeat(512 * 1024)), env, unexpected)).status, 413);
  const limited = { ...env, ANALYZE_LIMIT: { limit: async () => ({ success: false }) } };
  assert.equal((await analyze(request(), limited, unexpected)).status, 429);
});

test('Android receives only a completed assessment from the fixed provider', async () => {
  const upstream = finish => async (url, options) => {
    assert.equal(url, 'https://api.deepseek.com/chat/completions');
    assert.equal(options.headers.Authorization, 'Bearer fictional-provider-key');
    const [instructions, evidence] = JSON.parse(options.body).messages;
    assert.equal(instructions.role, 'system');
    const headings = instructions.content.split('\n').filter(line => line.endsWith('?'));
    assert.deepEqual(headings, [
      "What's happening with my vehicle right now?",
      'Is anything concerning?',
      'What should I check next?',
    ]);
    const nextStep = instructions.content.split('What should I check next?\n')[1];
    assert.match(nextStep, /fictional demo with no separate real-world complaint/);
    assert.match(nextStep, /No vehicle check is indicated from this fictional recording/);
    assert.match(nextStep, /choose at most one low-risk check or missing observation/);
    assert.deepEqual(evidence, { role: 'user', content: 'synthetic report' });
    return Response.json({ choices: [{ finish_reason: finish,
      message: { content: 'Sample assessment', reasoning_content: 'Not returned' } }] });
  };
  const result = await analyze(request(), env, upstream('stop'));
  assert.equal(result.status, 200);
  assert.equal(result.headers.get('cache-control'), 'no-store');
  assert.deepEqual(await result.json(), { assessment: 'Sample assessment' });
  assert.equal((await analyze(request(), env, upstream('length'))).status, 502);
  assert.equal((await analyze(request(), env, async () => new Response(null, { status: 429 }))).status, 429);
});

test('exhausted shared quota prevents provider calls', async () => {
  const blocked = { ...env, DAILY_QUOTA: { ...env.DAILY_QUOTA,
    get: () => ({ fetch: async () => new Response(null, { status: 429 }) }) } };
  assert.equal((await analyze(request(), blocked, async () => assert.fail('unexpected provider call'))).status, 429);
});

test('daily quota reserves exactly 50 requests and starts a fresh UTC day', async () => {
  let record = { day: '1970-01-01', count: 50 };
  const quota = new DailyQuota({ storage: {
    transaction: fn => fn({ get: async () => record, put: async (_, value) => { record = value; } }),
  } });
  for (let i = 0; i < 50; i++) assert.equal((await quota.fetch()).status, 204);
  assert.equal((await quota.fetch()).status, 429);
  assert.equal(record.count, 50);
});

for (const [id, name, url, secret] of [
  ['openai', 'OpenAI', 'https://api.openai.com/v1/chat/completions', 'OPENAI_API_KEY'],
  ['qwen', 'Qwen (Alibaba Cloud)', 'https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions', 'QWEN_API_KEY'],
  ['anthropic', 'Anthropic', 'https://api.anthropic.com/v1/messages', 'ANTHROPIC_API_KEY'],
]) {
  test(id + ' uses configured credentials and requires current consent', async () => {
    let calls = 0;
    const settings = { ...env, AI_PROVIDER: id, AI_MODEL: 'test-model', [secret]: 'synthetic-key' };
    const upstream = async (target, options) => {
      calls++;
      assert.equal(target, url);
      assert.equal(options.redirect, 'manual');
      const body = JSON.parse(options.body);
      assert.equal(body.model, 'test-model');
      assert.equal(body.thinking, undefined);
      if (id === 'anthropic') {
        assert.equal(options.headers['x-api-key'], 'synthetic-key');
        assert.equal(options.headers['anthropic-version'], '2023-06-01');
        assert.ok(body.system);
        return Response.json({ stop_reason: 'end_turn', content: [{ type: 'text', text: 'Assessment' }] });
      }
      assert.equal(options.headers.Authorization, 'Bearer synthetic-key');
      assert.equal(body[id === 'openai' ? 'max_completion_tokens' : 'max_tokens'], 2000);
      return Response.json({ choices: [{ finish_reason: 'stop', message: { content: 'Assessment' } }] });
    };
    const metadata = await analyze(new Request('https://relay.example/provider'), settings, upstream);
    assert.deepEqual(await metadata.json(), { name, revision: id + ':test-model' });
    assert.equal((await analyze(request(), settings, upstream)).status, 409);
    const analysis = request();
    analysis.headers.set('OpenWrench-AI-Provider', id + ':test-model');
    assert.equal((await analyze(analysis.clone(), { ...settings, AI_MODEL: 'new-model' }, upstream)).status, 409);
    assert.equal((await analyze(analysis.clone(), { ...settings, [secret]: '' }, upstream)).status, 503);
    assert.equal(calls, 0);
    assert.deepEqual(await (await analyze(analysis, settings, upstream)).json(), { assessment: 'Assessment' });
    assert.equal(calls, 1);
  });
}
