<script lang="ts">
	import i18n from '$lib/i18n';
	import { Button, Input, Textarea } from '$lib/components/ui';
	import { BookA, Calculator, ScrollText, Plus, Trash2, Pencil, Search, Loader2, AlertTriangle } from 'lucide-svelte';
	import glossaryService, { type ICatalogTerm, type ICatalogMetric, type ICatalogRule } from '$lib/service/glossary';
	import message from '$lib/utils/message';

	interface Props {
		dataSourceId: number | null;
		/** Default database / schema for metric table references without them. */
		databaseName?: string;
		schemaName?: string;
	}

	let { dataSourceId, databaseName = '', schemaName = '' }: Props = $props();

	let tab = $state<'terms' | 'metrics' | 'rules'>('terms');
	let filter = $state('');
	let loading = $state(false);
	let terms = $state<ICatalogTerm[]>([]);
	let metrics = $state<ICatalogMetric[]>([]);
	let rules = $state<ICatalogRule[]>([]);
	let editingRule = $state<ICatalogRule | null>(null);
	let ruleWarning = $state('');

	// Editor state: one form at a time.
	let editingTerm = $state<ICatalogTerm | null>(null);
	let editingMetric = $state<ICatalogMetric | null>(null);
	let aliasesText = $state('');
	let saving = $state(false);
	let formError = $state('');

	const AGGREGATIONS = ['', 'additive', 'distinct', 'non_additive'];

	async function load() {
		if (!dataSourceId) return;
		loading = true;
		try {
			const [t, m, r] = await Promise.all([
				glossaryService.listTerms({ dataSourceId }),
				glossaryService.listMetrics({ dataSourceId }),
				glossaryService.listRules({ dataSourceId })
			]);
			terms = t || [];
			metrics = m || [];
			rules = r || [];
		} catch (e) {
			console.error('Failed to load glossary:', e);
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		// Reload whenever the data source changes.
		dataSourceId;
		editingTerm = null;
		editingMetric = null;
		editingRule = null;
		load();
	});

	const norm = (s: string | null | undefined) => (s || '').toLowerCase();
	let filteredTerms = $derived(
		terms.filter((t) => {
			const q = norm(filter);
			return !q || norm(t.canonicalValue).includes(q) || t.aliases.some((a) => norm(a).includes(q));
		})
	);
	let filteredMetrics = $derived(
		metrics.filter((m) => {
			const q = norm(filter);
			return !q || norm(m.name).includes(q) || m.aliases.some((a) => norm(a).includes(q));
		})
	);

	function parseAliases(text: string): string[] {
		return text
			.split(',')
			.map((a) => a.trim())
			.filter((a) => a.length > 0);
	}

	function startTerm(t?: ICatalogTerm) {
		if (!dataSourceId) return;
		editingMetric = null;
		formError = '';
		editingTerm = t
			? { ...t }
			: { dataSourceId, canonicalValue: '', aliases: [], columnRef: '', note: '' };
		aliasesText = (editingTerm.aliases || []).join(', ');
	}

	function startMetric(m?: ICatalogMetric) {
		if (!dataSourceId) return;
		editingTerm = null;
		formError = '';
		editingMetric = m
			? { ...m, aggregation: m.aggregation || '' }
			: {
					dataSourceId,
					name: '',
					aliases: [],
					description: '',
					tableRef: '',
					expression: '',
					filters: '',
					grain: '',
					caveats: '',
					aggregation: ''
				};
		aliasesText = (editingMetric.aliases || []).join(', ');
	}

	function cancel() {
		editingTerm = null;
		editingMetric = null;
		editingRule = null;
		formError = '';
	}

	let filteredRules = $derived(
		rules.filter((r) => {
			const q = norm(filter);
			return !q || norm(r.ruleText).includes(q) || norm(r.tableRef).includes(q);
		})
	);

	function startRule(r?: ICatalogRule) {
		if (!dataSourceId) return;
		editingTerm = null;
		editingMetric = null;
		formError = '';
		ruleWarning = '';
		editingRule = r ? { ...r } : { dataSourceId, ruleText: '', tableRef: '', enabled: true };
	}

	async function saveRule() {
		if (!editingRule) return;
		saving = true;
		formError = '';
		try {
			const saved = await glossaryService.saveRule({ ...editingRule });
			message.success(i18n('catalog.glossary.saved'));
			// Keep descriptive-rule advice visible after the editor closes.
			ruleWarning = saved?.styleWarning || '';
			editingRule = null;
			await load();
		} catch (e: any) {
			formError = e?.errorMessage || e?.message || 'Save failed';
		} finally {
			saving = false;
		}
	}

	async function toggleRule(r: ICatalogRule) {
		await glossaryService.saveRule({ ...r, enabled: !r.enabled });
		await load();
	}

	async function removeRule(r: ICatalogRule) {
		if (!r.id) return;
		await glossaryService.deleteRule({ id: r.id });
		if (editingRule?.id === r.id) editingRule = null;
		await load();
	}

	async function saveTerm() {
		if (!editingTerm) return;
		saving = true;
		formError = '';
		try {
			await glossaryService.saveTerm({ ...editingTerm, aliases: parseAliases(aliasesText) });
			message.success(i18n('catalog.glossary.saved'));
			editingTerm = null;
			await load();
		} catch (e: any) {
			formError = e?.errorMessage || e?.message || 'Save failed';
		} finally {
			saving = false;
		}
	}

	async function saveMetric() {
		if (!editingMetric) return;
		saving = true;
		formError = '';
		try {
			await glossaryService.saveMetric({
				dataSourceId: editingMetric.dataSourceId,
				databaseName: databaseName || undefined,
				schemaName: schemaName || undefined,
				metric: { ...editingMetric, aliases: parseAliases(aliasesText) }
			});
			message.success(i18n('catalog.glossary.saved'));
			editingMetric = null;
			await load();
		} catch (e: any) {
			formError = e?.errorMessage || e?.message || 'Save failed';
		} finally {
			saving = false;
		}
	}

	async function removeTerm(t: ICatalogTerm) {
		if (!t.id) return;
		await glossaryService.deleteTerm({ id: t.id });
		if (editingTerm?.id === t.id) editingTerm = null;
		await load();
	}

	async function removeMetric(m: ICatalogMetric) {
		if (!m.id) return;
		await glossaryService.deleteMetric({ id: m.id });
		if (editingMetric?.id === m.id) editingMetric = null;
		await load();
	}

	const labelClass = 'block text-[11px] font-medium text-muted-foreground mb-1';
</script>

<div class="flex-1 overflow-hidden flex flex-col">
	<!-- Toolbar -->
	<div class="flex items-center gap-2 px-3 py-2 border-b border-border/50 bg-card/30 shrink-0">
		<div class="flex items-center rounded-md bg-muted p-0.5">
			<button
				class="flex items-center gap-1 px-2.5 py-1 rounded text-xs font-medium transition-all
					{tab === 'terms' ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'}"
				onclick={() => { tab = 'terms'; cancel(); }}
			>
				<BookA size={13} />{i18n('catalog.glossary.terms')}
				<span class="text-[10px] text-muted-foreground">{terms.length}</span>
			</button>
			<button
				class="flex items-center gap-1 px-2.5 py-1 rounded text-xs font-medium transition-all
					{tab === 'metrics' ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'}"
				onclick={() => { tab = 'metrics'; cancel(); }}
			>
				<Calculator size={13} />{i18n('catalog.glossary.metrics')}
				<span class="text-[10px] text-muted-foreground">{metrics.length}</span>
			</button>
			<button
				class="flex items-center gap-1 px-2.5 py-1 rounded text-xs font-medium transition-all
					{tab === 'rules' ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'}"
				onclick={() => { tab = 'rules'; cancel(); }}
			>
				<ScrollText size={13} />{i18n('catalog.glossary.rules')}
				<span class="text-[10px] text-muted-foreground">{rules.length}</span>
			</button>
		</div>
		<div class="relative flex-1 max-w-xs">
			<Search size={13} class="absolute left-2 top-1/2 -translate-y-1/2 text-muted-foreground" />
			<Input bind:value={filter} placeholder={i18n('catalog.glossary.filter')} class="h-8 pl-7 text-xs" />
		</div>
		<div class="flex-1"></div>
		<Button
			size="sm"
			class="h-8 text-xs gap-1"
			disabled={!dataSourceId}
			onclick={() => (tab === 'terms' ? startTerm() : tab === 'metrics' ? startMetric() : startRule())}
		>
			<Plus size={13} />{tab === 'terms'
				? i18n('catalog.glossary.addTerm')
				: tab === 'metrics'
					? i18n('catalog.glossary.addMetric')
					: i18n('catalog.glossary.addRule')}
		</Button>
	</div>

	<div class="flex-1 overflow-auto p-3 space-y-3">
		{#if !dataSourceId}
			<p class="text-sm text-muted-foreground">{i18n('catalog.glossary.pickDataSource')}</p>
		{:else}
			<p class="text-xs text-muted-foreground">
				{tab === 'terms'
					? i18n('catalog.glossary.termsHelp')
					: tab === 'metrics'
						? i18n('catalog.glossary.metricsHelp')
						: i18n('catalog.glossary.rulesHelp')}
			</p>

			{#if editingRule}
				<div class="rounded-lg border border-border bg-card p-3 space-y-2">
					<div>
						<label class={labelClass} for="rule-text">{i18n('catalog.glossary.ruleText')}</label>
						<Textarea id="rule-text" bind:value={editingRule.ruleText} rows={3} placeholder={i18n('catalog.glossary.rulePlaceholder')} class="text-xs" />
					</div>
					<div>
						<label class={labelClass} for="rule-table">{i18n('catalog.glossary.ruleScope')}</label>
						<Input id="rule-table" value={editingRule.tableRef || ''} oninput={(e) => { if (editingRule) editingRule.tableRef = (e.target as HTMLInputElement).value; }} placeholder="schema.table" class="h-8 text-xs font-mono" />
					</div>
					{#if formError}<p class="text-xs text-destructive">{formError}</p>{/if}
					<div class="flex justify-end gap-2">
						<Button variant="ghost" size="sm" class="h-8 text-xs" onclick={cancel}>{i18n('catalog.glossary.cancel')}</Button>
						<Button size="sm" class="h-8 text-xs" disabled={saving || !editingRule.ruleText.trim()} onclick={saveRule}>
							{#if saving}<Loader2 size={13} class="animate-spin" />{/if}{i18n('catalog.glossary.save')}
						</Button>
					</div>
				</div>
			{/if}

			{#if tab === 'rules' && ruleWarning}
				<div class="flex items-start gap-2 rounded-lg border border-amber-500/40 bg-amber-500/10 p-2.5 text-xs text-amber-700 dark:text-amber-300">
					<AlertTriangle size={14} class="shrink-0 mt-0.5" />
					<span>{ruleWarning}</span>
				</div>
			{/if}

			<!-- Editor -->
			{#if editingTerm}
				<div class="rounded-lg border border-border bg-card p-3 space-y-2">
					<div class="grid grid-cols-1 md:grid-cols-2 gap-2">
						<div>
							<label class={labelClass} for="term-canonical">{i18n('catalog.glossary.canonicalValue')}</label>
							<Input id="term-canonical" bind:value={editingTerm.canonicalValue} class="h-8 text-xs" />
						</div>
						<div>
							<label class={labelClass} for="term-aliases">{i18n('catalog.glossary.aliases')}</label>
							<Input id="term-aliases" bind:value={aliasesText} placeholder="abbr, nickname, ..." class="h-8 text-xs" />
						</div>
						<div>
							<label class={labelClass} for="term-column">{i18n('catalog.glossary.columnRef')}</label>
							<Input id="term-column" value={editingTerm.columnRef || ''} oninput={(e) => { if (editingTerm) editingTerm.columnRef = (e.target as HTMLInputElement).value; }} placeholder="schema.table.column" class="h-8 text-xs font-mono" />
						</div>
						<div>
							<label class={labelClass} for="term-note">{i18n('catalog.glossary.note')}</label>
							<Input id="term-note" value={editingTerm.note || ''} oninput={(e) => { if (editingTerm) editingTerm.note = (e.target as HTMLInputElement).value; }} class="h-8 text-xs" />
						</div>
					</div>
					{#if formError}<p class="text-xs text-destructive">{formError}</p>{/if}
					<div class="flex justify-end gap-2">
						<Button variant="ghost" size="sm" class="h-8 text-xs" onclick={cancel}>{i18n('catalog.glossary.cancel')}</Button>
						<Button size="sm" class="h-8 text-xs" disabled={saving || !editingTerm.canonicalValue.trim()} onclick={saveTerm}>
							{#if saving}<Loader2 size={13} class="animate-spin" />{/if}{i18n('catalog.glossary.save')}
						</Button>
					</div>
				</div>
			{/if}

			{#if editingMetric}
				<div class="rounded-lg border border-border bg-card p-3 space-y-2">
					<div class="grid grid-cols-1 md:grid-cols-2 gap-2">
						<div>
							<label class={labelClass} for="metric-name">{i18n('catalog.glossary.metricName')}</label>
							<Input id="metric-name" bind:value={editingMetric.name} class="h-8 text-xs" />
						</div>
						<div>
							<label class={labelClass} for="metric-aliases">{i18n('catalog.glossary.aliases')}</label>
							<Input id="metric-aliases" bind:value={aliasesText} placeholder="abbr, nickname, ..." class="h-8 text-xs" />
						</div>
						<div>
							<label class={labelClass} for="metric-table">{i18n('catalog.glossary.tableRef')}</label>
							<Input id="metric-table" bind:value={editingMetric.tableRef} placeholder="schema.table" class="h-8 text-xs font-mono" />
						</div>
						<div>
							<label class={labelClass} for="metric-agg">{i18n('catalog.glossary.aggregation')}</label>
							<select
								id="metric-agg"
								class="h-8 w-full rounded-md border border-input bg-background px-2 text-xs"
								value={editingMetric.aggregation || ''}
								onchange={(e) => { if (editingMetric) editingMetric.aggregation = (e.target as HTMLSelectElement).value; }}
							>
								{#each AGGREGATIONS as agg}
									<option value={agg}>{agg ? i18n(`catalog.aggregation.${agg}`) : i18n('catalog.glossary.aggregationAuto')}</option>
								{/each}
							</select>
						</div>
						<div class="md:col-span-2">
							<label class={labelClass} for="metric-expr">{i18n('catalog.glossary.expression')}</label>
							<Input id="metric-expr" bind:value={editingMetric.expression} placeholder="SUM(amount)" class="h-8 text-xs font-mono" />
						</div>
						<div class="md:col-span-2">
							<label class={labelClass} for="metric-filters">{i18n('catalog.glossary.filters')}</label>
							<Input id="metric-filters" value={editingMetric.filters || ''} oninput={(e) => { if (editingMetric) editingMetric.filters = (e.target as HTMLInputElement).value; }} placeholder="status <> 'cancelled'" class="h-8 text-xs font-mono" />
						</div>
						<div>
							<label class={labelClass} for="metric-grain">{i18n('catalog.glossary.grain')}</label>
							<Input id="metric-grain" value={editingMetric.grain || ''} oninput={(e) => { if (editingMetric) editingMetric.grain = (e.target as HTMLInputElement).value; }} class="h-8 text-xs" />
						</div>
						<div>
							<label class={labelClass} for="metric-desc">{i18n('catalog.glossary.description')}</label>
							<Input id="metric-desc" value={editingMetric.description || ''} oninput={(e) => { if (editingMetric) editingMetric.description = (e.target as HTMLInputElement).value; }} class="h-8 text-xs" />
						</div>
						<div class="md:col-span-2">
							<label class={labelClass} for="metric-caveats">{i18n('catalog.glossary.caveats')}</label>
							<Textarea id="metric-caveats" value={editingMetric.caveats || ''} oninput={(e) => { if (editingMetric) editingMetric.caveats = (e.target as HTMLTextAreaElement).value; }} rows={2} class="text-xs" />
						</div>
					</div>
					{#if formError}<p class="text-xs text-destructive">{formError}</p>{/if}
					<div class="flex justify-end gap-2">
						<Button variant="ghost" size="sm" class="h-8 text-xs" onclick={cancel}>{i18n('catalog.glossary.cancel')}</Button>
						<Button
							size="sm"
							class="h-8 text-xs"
							disabled={saving || !editingMetric.name.trim() || !editingMetric.tableRef.trim() || !editingMetric.expression.trim()}
							onclick={saveMetric}
						>
							{#if saving}<Loader2 size={13} class="animate-spin" />{/if}{i18n('catalog.glossary.save')}
						</Button>
					</div>
				</div>
			{/if}

			<!-- Lists -->
			{#if loading}
				<div class="flex items-center gap-2 text-xs text-muted-foreground"><Loader2 size={13} class="animate-spin" />Loading...</div>
			{:else if tab === 'rules'}
				{#if filteredRules.length === 0}
					<p class="text-xs text-muted-foreground">{i18n('catalog.glossary.empty')}</p>
				{:else}
					<div class="rounded-lg border border-border overflow-hidden">
						<table class="w-full text-xs">
							<thead class="bg-muted/50 text-muted-foreground">
								<tr>
									<th class="w-16 text-left font-medium px-3 py-1.5">{i18n('catalog.glossary.enabled')}</th>
									<th class="text-left font-medium px-3 py-1.5">{i18n('catalog.glossary.ruleText')}</th>
									<th class="text-left font-medium px-3 py-1.5">{i18n('catalog.glossary.ruleScope')}</th>
									<th class="w-16"></th>
								</tr>
							</thead>
							<tbody>
								{#each filteredRules as r (r.id)}
									<tr class="border-t border-border hover:bg-accent/30 align-top {r.enabled ? '' : 'opacity-50'}">
										<td class="px-3 py-1.5">
											<input type="checkbox" checked={r.enabled} onchange={() => toggleRule(r)} class="accent-primary" aria-label={i18n('catalog.glossary.enabled')} />
										</td>
										<td class="px-3 py-1.5 whitespace-pre-wrap">{r.ruleText}</td>
										<td class="px-3 py-1.5 font-mono text-muted-foreground">{r.tableRef || ''}</td>
										<td class="px-2 py-1.5">
											<div class="flex justify-end gap-1">
												<button class="p-1 rounded hover:bg-accent text-muted-foreground" title="Edit" onclick={() => startRule(r)}><Pencil size={12} /></button>
												<button class="p-1 rounded hover:bg-destructive/10 text-muted-foreground hover:text-destructive" title="Delete" onclick={() => removeRule(r)}><Trash2 size={12} /></button>
											</div>
										</td>
									</tr>
								{/each}
							</tbody>
						</table>
					</div>
				{/if}
			{:else if tab === 'terms'}
				{#if filteredTerms.length === 0}
					<p class="text-xs text-muted-foreground">{i18n('catalog.glossary.empty')}</p>
				{:else}
					<div class="rounded-lg border border-border overflow-hidden">
						<table class="w-full text-xs">
							<thead class="bg-muted/50 text-muted-foreground">
								<tr>
									<th class="text-left font-medium px-3 py-1.5">{i18n('catalog.glossary.canonicalValue')}</th>
									<th class="text-left font-medium px-3 py-1.5">{i18n('catalog.glossary.aliases')}</th>
									<th class="text-left font-medium px-3 py-1.5">{i18n('catalog.glossary.columnRef')}</th>
									<th class="text-left font-medium px-3 py-1.5">{i18n('catalog.glossary.note')}</th>
									<th class="w-16"></th>
								</tr>
							</thead>
							<tbody>
								{#each filteredTerms as t (t.id)}
									<tr class="border-t border-border hover:bg-accent/30">
										<td class="px-3 py-1.5 font-medium">{t.canonicalValue}</td>
										<td class="px-3 py-1.5">
											<div class="flex flex-wrap gap-1">
												{#each t.aliases as a}
													<span class="px-1.5 py-px rounded bg-primary/10 text-primary text-[10px]">{a}</span>
												{/each}
											</div>
										</td>
										<td class="px-3 py-1.5 font-mono text-muted-foreground">{t.columnRef || ''}</td>
										<td class="px-3 py-1.5 text-muted-foreground truncate max-w-xs" title={t.note || ''}>{t.note || ''}</td>
										<td class="px-2 py-1.5">
											<div class="flex justify-end gap-1">
												<button class="p-1 rounded hover:bg-accent text-muted-foreground" title="Edit" onclick={() => startTerm(t)}><Pencil size={12} /></button>
												<button class="p-1 rounded hover:bg-destructive/10 text-muted-foreground hover:text-destructive" title="Delete" onclick={() => removeTerm(t)}><Trash2 size={12} /></button>
											</div>
										</td>
									</tr>
								{/each}
							</tbody>
						</table>
					</div>
				{/if}
			{:else if filteredMetrics.length === 0}
				<p class="text-xs text-muted-foreground">{i18n('catalog.glossary.empty')}</p>
			{:else}
				<div class="rounded-lg border border-border overflow-hidden">
					<table class="w-full text-xs">
						<thead class="bg-muted/50 text-muted-foreground">
							<tr>
								<th class="text-left font-medium px-3 py-1.5">{i18n('catalog.glossary.metricName')}</th>
								<th class="text-left font-medium px-3 py-1.5">{i18n('catalog.glossary.tableRef')}</th>
								<th class="text-left font-medium px-3 py-1.5">{i18n('catalog.glossary.expression')}</th>
								<th class="text-left font-medium px-3 py-1.5">{i18n('catalog.glossary.filters')}</th>
								<th class="w-16"></th>
							</tr>
						</thead>
						<tbody>
							{#each filteredMetrics as m (m.id)}
								<tr class="border-t border-border hover:bg-accent/30 align-top">
									<td class="px-3 py-1.5">
										<div class="font-medium">{m.name}</div>
										{#if m.aliases.length}
											<div class="flex flex-wrap gap-1 mt-0.5">
												{#each m.aliases as a}
													<span class="px-1.5 py-px rounded bg-primary/10 text-primary text-[10px]">{a}</span>
												{/each}
											</div>
										{/if}
										{#if m.description}<div class="text-muted-foreground mt-0.5">{m.description}</div>{/if}
									</td>
									<td class="px-3 py-1.5 font-mono text-muted-foreground">{m.tableRef}</td>
									<td class="px-3 py-1.5 font-mono">{m.expression}</td>
									<td class="px-3 py-1.5 font-mono text-muted-foreground">{m.filters || ''}</td>
									<td class="px-2 py-1.5">
										<div class="flex justify-end gap-1">
											<button class="p-1 rounded hover:bg-accent text-muted-foreground" title="Edit" onclick={() => startMetric(m)}><Pencil size={12} /></button>
											<button class="p-1 rounded hover:bg-destructive/10 text-muted-foreground hover:text-destructive" title="Delete" onclick={() => removeMetric(m)}><Trash2 size={12} /></button>
										</div>
									</td>
								</tr>
							{/each}
						</tbody>
					</table>
				</div>
			{/if}
		{/if}
	</div>
</div>
