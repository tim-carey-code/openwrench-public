# OpenWrench diagnostic AI relay

Deploy this optional relay to Cloudflare Workers. Recording, local diagnosis,
and manual ZIP export still work without it. In Diagnosis History, open a saved
diagnosis and choose **Analyze with AI**. The app already knows the HTTPS URL.
There is no sign-in, device approval, or token. Review the saved report, selected recorded evidence and symptom note before
sending. Full readings files and drive archives are not sent. The assessment
answers what happened during the recording, what warrants attention, and what
single check would be most useful next. See the [four comparison cases](../../docs/ai-diagnostic-handoff.md#what-a-better-in-app-answer-looks-like).

From this directory, use Wrangler to deploy to the Workers Free plan:

```sh
npx wrangler@4 deploy
npx wrangler@4 secret put DEEPSEEK_API_KEY
```

Enter the provider key only at the secret prompt. The public endpoint accepts
five requests per minute per IP per Cloudflare location. A single Durable Object
also caps upstream attempts at 50 per UTC day across locations. Provider
failures count against the quota; DeepSeek is billed separately.
The relay does not store reports; DeepSeek's data handling applies to forwarded
reports. Do not enable request-body or authorization-header logging.

Local checks: `node --test relay.test.mjs` and
`npx wrangler@4 deploy --dry-run`. After deployment, submit a fictional report to
`POST /analyze` and verify the response before using real evidence.

## Switch providers without rebuilding Android

In the Worker's **Settings → Variables and Secrets**, add the destination
provider's secret, set `AI_PROVIDER` and `AI_MODEL` together, then deploy
the configuration. Use a model ID available to your account that supports
text requests to the API below. If you deploy through Wrangler, persist the
two non-secret variables in its configuration only if you intend to override
dashboard values. The checked-in `keep_vars` setting preserves dashboard values. To roll back, restore the previous provider and model.

| AI_PROVIDER | Worker secret | API |
| --- | --- | --- |
| deepseek | DEEPSEEK_API_KEY | Chat Completions |
| openai | OPENAI_API_KEY | Chat Completions |
| qwen | QWEN_API_KEY | Alibaba Cloud Model Studio international Chat Completions |
| anthropic | ANTHROPIC_API_KEY | Messages |

An unset provider and model preserve the existing DeepSeek Flash configuration.
There is no automatic cross-provider fallback. Android gets the provider name
before showing consent and binds the submission to that provider and model.
A stale confirmation is rejected before consuming upstream quota. Older APKs
that explicitly disclose DeepSeek can still send to DeepSeek, but cannot send
to another vendor. Rate and daily limits are shared across vendors.
