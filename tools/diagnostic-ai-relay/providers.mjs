const providers = {
  deepseek: { label: 'DeepSeek', url: 'https://api.deepseek.com/chat/completions', key: 'DEEPSEEK_API_KEY' },
  openai: { label: 'OpenAI', url: 'https://api.openai.com/v1/chat/completions', key: 'OPENAI_API_KEY' },
  qwen: { label: 'Qwen (Alibaba Cloud)', url: 'https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions', key: 'QWEN_API_KEY' },
  anthropic: { label: 'Anthropic', url: 'https://api.anthropic.com/v1/messages', key: 'ANTHROPIC_API_KEY' },
};

export function configuredProvider(env) {
  const id = env.AI_PROVIDER ?? 'deepseek';
  if (!Object.hasOwn(providers, id)) return null;
  const provider = providers[id];
  const model = env.AI_MODEL ?? (id === 'deepseek' ? 'deepseek-flash' : '');
  const key = env[provider.key]?.trim();
  if (!key || !/^[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,127}$/.test(model)) return null;
  return { ...provider, id, model, apiKey: key };
}

export function providerRequest(provider, system, evidence) {
  const headers = { 'Content-Type': 'application/json' };
  const body = { model: provider.model, messages: [{ role: 'user', content: evidence }] };
  if (provider.id === 'anthropic') {
    headers['x-api-key'] = provider.apiKey;
    headers['anthropic-version'] = '2023-06-01';
    body.system = system;
    body.max_tokens = 2000;
  } else {
    headers.Authorization = 'Bearer ' + provider.apiKey;
    body.messages.unshift({ role: 'system', content: system });
    if (provider.id === 'openai') body.max_completion_tokens = 2000;
    else body.max_tokens = 2000;
    if (provider.id === 'deepseek') body.thinking = { type: 'disabled' };
  }
  return { headers, body: JSON.stringify(body) };
}

export function completedAssessment(provider, result) {
  if (provider.id === 'anthropic') {
    if (!['end_turn', 'stop_sequence'].includes(result.stop_reason) || !Array.isArray(result.content)) return null;
    return result.content.filter(block => block.type === 'text' && typeof block.text === 'string')
      .map(block => block.text).join('\n');
  }
  const choice = result.choices?.[0];
  return choice?.finish_reason === 'stop' ? choice.message?.content : null;
}
