<template>
  <aside class="step-panel" v-if="step">
    <header class="panel-header">
      <span class="panel-title">{{ t('workflows.canvas.inspector.title') }}</span>
      <div class="panel-actions">
        <button class="panel-btn" :title="t('workflows.canvas.inspector.duplicate')" @click="onDuplicate">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
               stroke-linecap="round" stroke-linejoin="round">
            <rect x="9" y="9" width="13" height="13" rx="2"/>
            <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>
          </svg>
        </button>
        <button class="panel-btn danger" :title="t('workflows.canvas.inspector.delete')" @click="onDelete">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
               stroke-linecap="round" stroke-linejoin="round">
            <polyline points="3 6 5 6 21 6"/>
            <path d="M19 6l-2 14H7L5 6"/>
            <path d="M10 11v6"/>
            <path d="M14 11v6"/>
            <path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>
          </svg>
        </button>
      </div>
    </header>

    <!-- Shared fields every mode honors. -->
    <fieldset class="panel-section">
      <legend>{{ t('workflows.canvas.inspector.shared') }}</legend>
      <label class="panel-field">
        <span class="field-label">{{ t('workflows.canvas.fields.name') }}</span>
        <input
          class="mc-input"
          :value="step.name ?? ''"
          @input="patch({ name: ($event.target as HTMLInputElement).value })"
          spellcheck="false"
          :placeholder="t('workflows.canvas.fields.namePlaceholder')"
        />
      </label>

      <label class="panel-field" v-if="modeNeedsAgent">
        <span class="field-label">{{ t('workflows.canvas.nodeAgent') }}</span>
        <!-- Agent picker — drops down the workspace's agents and falls
             back to a free-text input when the operator wants to type a
             name that isn't in the list (legacy drafts, agent created in
             a different workspace by an admin, etc.). -->
        <div class="agent-picker">
          <select
            v-if="!useFreeAgentName"
            class="mc-input"
            :value="agentSelectValue"
            @change="onAgentSelect"
          >
            <option value="">{{ t('workflows.canvas.fields.agentPlaceholder') }}</option>
            <option v-for="a in availableAgents" :key="a.id" :value="a.name">
              {{ a.name }}<template v-if="a.title"> — {{ a.title }}</template>
            </option>
            <option v-if="agentNotInList" :value="step.agentName ?? ''" disabled>
              {{ t('workflows.canvas.fields.agentMissing', { name: step.agentName }) }}
            </option>
            <option value="__custom__">{{ t('workflows.canvas.fields.agentUseCustom') }}</option>
          </select>
          <input
            v-else
            class="mc-input"
            :value="step.agentName ?? ''"
            @input="patch({ agentName: ($event.target as HTMLInputElement).value })"
            spellcheck="false"
            :placeholder="t('workflows.canvas.fields.agentPlaceholder')"
          />
          <button
            v-if="useFreeAgentName"
            type="button"
            class="agent-toggle"
            :title="t('workflows.canvas.fields.agentBackToList')"
            @click="useFreeAgentName = false"
          >×</button>
        </div>
      </label>

      <label class="panel-field" v-if="modeNeedsAgent">
        <span class="field-label">{{ t('workflows.canvas.fields.promptTemplate') }}</span>
        <textarea
          class="mc-textarea"
          :value="step.promptTemplate ?? ''"
          @input="patch({ promptTemplate: ($event.target as HTMLTextAreaElement).value })"
          spellcheck="false"
          rows="3"
          :placeholder="t('workflows.canvas.fields.promptPlaceholder')"
        />
      </label>

      <div class="panel-row" v-if="modeNeedsAgent">
        <label class="panel-field">
          <span class="field-label">{{ t('workflows.canvas.fields.outputVar') }}</span>
          <input
            class="mc-input"
            :value="step.outputVar ?? ''"
            @input="patch({ outputVar: ($event.target as HTMLInputElement).value })"
            spellcheck="false"
            :placeholder="t('workflows.canvas.fields.outputVarPlaceholder')"
          />
        </label>
        <label class="panel-field">
          <span class="field-label">{{ t('workflows.canvas.fields.outputContentType') }}</span>
          <select
            class="mc-input"
            :value="step.outputContentType ?? 'text'"
            @change="patch({ outputContentType: ($event.target as HTMLSelectElement).value })"
          >
            <option value="text">text</option>
            <option value="json">json</option>
            <option value="bytes">bytes</option>
          </select>
        </label>
      </div>
    </fieldset>

    <!-- Per-mode editor. -->
    <fieldset class="panel-section">
      <legend>{{ t('workflows.canvas.inspector.modeFields', { mode: localizedModeLabel }) }}</legend>

      <label class="panel-field">
        <span class="field-label">{{ t('workflows.canvas.modeLabel') }}</span>
        <select class="mc-input" :value="modeType" @change="onModeChange">
          <option value="sequential">{{ modeOptionLabel('sequential') }}</option>
          <option value="fan_out">{{ modeOptionLabel('fan_out') }}</option>
          <option value="collect">{{ modeOptionLabel('collect') }}</option>
          <option value="conditional">{{ modeOptionLabel('conditional') }}</option>
          <option value="await_approval">{{ modeOptionLabel('await_approval') }}</option>
          <option value="dispatch_channel">{{ modeOptionLabel('dispatch_channel') }}</option>
          <option value="write_memory">{{ modeOptionLabel('write_memory') }}</option>
        </select>
      </label>

      <!-- conditional -->
      <label class="panel-field" v-if="modeType === 'conditional'">
        <span class="field-label">{{ t('workflows.canvas.nodeExpression') }}</span>
        <input
          class="mc-input mono"
          :value="modeField('expression', '')"
          @input="patchMode({ expression: ($event.target as HTMLInputElement).value })"
          spellcheck="false"
          placeholder="{{ inputs.payload != null }}"
        />
      </label>

      <!-- await_approval -->
      <template v-if="modeType === 'await_approval'">
        <label class="panel-field">
          <span class="field-label">{{ t('workflows.canvas.fields.approvalKind') }}</span>
          <input
            class="mc-input"
            :value="modeField('approvalKind', '')"
            @input="patchMode({ approvalKind: ($event.target as HTMLInputElement).value })"
            spellcheck="false"
            placeholder="manual / manager / oncall"
          />
        </label>
        <label class="panel-field">
          <span class="field-label">{{ t('workflows.canvas.fields.approverChannels') }}</span>
          <input
            class="mc-input"
            :value="(modeField('approverChannels', []) as string[]).join(', ')"
            @change="patchMode({ approverChannels: parseList(($event.target as HTMLInputElement).value) })"
            spellcheck="false"
            placeholder="web, feishu"
          />
        </label>
        <label class="panel-field">
          <span class="field-label">{{ t('workflows.canvas.fields.approvalMessage') }}</span>
          <input
            class="mc-input"
            :value="modeField('approvalMessage', '')"
            @input="patchMode({ approvalMessage: ($event.target as HTMLInputElement).value })"
            spellcheck="false"
          />
        </label>
        <label class="panel-field">
          <span class="field-label">{{ t('workflows.canvas.fields.timeoutSecs') }}</span>
          <input
            type="number"
            class="mc-input"
            :value="modeField('timeoutSecs', 3600)"
            @input="patchMode({ timeoutSecs: parseInt(($event.target as HTMLInputElement).value, 10) || null })"
            min="0"
          />
        </label>
      </template>

      <!-- dispatch_channel -->
      <template v-if="modeType === 'dispatch_channel'">
        <label class="panel-field">
          <span class="field-label">{{ t('workflows.canvas.fields.channels') }}</span>
          <input
            class="mc-input"
            :value="(modeField('channels', []) as string[]).join(', ')"
            @change="patchMode({ channels: parseList(($event.target as HTMLInputElement).value) })"
            spellcheck="false"
            placeholder="feishu, dingtalk"
          />
        </label>
        <label class="panel-field">
          <span class="field-label">{{ t('workflows.canvas.fields.dispatchContent') }}</span>
          <textarea
            class="mc-textarea"
            :value="modeField('content', '')"
            @input="patchMode({ content: ($event.target as HTMLTextAreaElement).value })"
            rows="3"
            spellcheck="false"
            placeholder="Notification: {{ inputs.payload }}"
          />
        </label>
      </template>

      <!-- write_memory -->
      <template v-if="modeType === 'write_memory'">
        <label class="panel-field">
          <span class="field-label">{{ t('workflows.canvas.fields.employeeId') }}</span>
          <input
            class="mc-input"
            :value="modeField('employeeId', '')"
            @input="patchMode({ employeeId: ($event.target as HTMLInputElement).value })"
            spellcheck="false"
          />
        </label>
        <label class="panel-field">
          <span class="field-label">{{ t('workflows.canvas.fields.memoryFile') }}</span>
          <input
            class="mc-input"
            :value="modeField('file', '')"
            @input="patchMode({ file: ($event.target as HTMLInputElement).value })"
            spellcheck="false"
            placeholder="workspace.md"
          />
        </label>
        <label class="panel-field">
          <span class="field-label">{{ t('workflows.canvas.nodeMergeStrategy') }}</span>
          <select
            class="mc-input"
            :value="modeField('mergeStrategy', 'append')"
            @change="patchMode({ mergeStrategy: ($event.target as HTMLSelectElement).value })"
          >
            <option value="append">append</option>
            <option value="prepend">prepend</option>
            <option value="replace_section">replace_section</option>
            <option value="overwrite">overwrite</option>
          </select>
        </label>
        <label class="panel-field">
          <span class="field-label">{{ t('workflows.canvas.fields.memoryContent') }}</span>
          <textarea
            class="mc-textarea"
            :value="modeField('content', '')"
            @input="patchMode({ content: ($event.target as HTMLTextAreaElement).value })"
            rows="3"
            spellcheck="false"
          />
        </label>
      </template>

      <p v-if="modeType === 'fan_out' || modeType === 'collect' || modeType === 'sequential'" class="panel-hint">
        {{ t('workflows.canvas.inspector.modeNoFields') }}
      </p>
    </fieldset>

    <details class="panel-section raw-section">
      <summary>{{ t('workflows.canvas.inspector.rawHeader') }}</summary>
      <pre class="raw-json">{{ rawJson }}</pre>
    </details>
  </aside>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { RawStep } from '@/composables/useWorkflowGraph'

/** Minimal agent shape the panel needs to render the picker — kept
 *  inside the component so the panel doesn't have to import the
 *  full workspace agent type. */
export interface AgentOption {
  id: number | string
  name: string
  title?: string
}

interface Props {
  /** The step the panel currently edits, or null. */
  step: RawStep | null
  /** Index of the step inside `steps[]` — used by the parent to scope patches. */
  index: number
  /** Workspace-scoped agent list rendered as a dropdown. */
  availableAgents?: AgentOption[]
}

const props = withDefaults(defineProps<Props>(), {
  availableAgents: () => [],
})
const emit = defineEmits<{
  (e: 'patch', payload: { index: number; patch: Partial<RawStep> }): void
  (e: 'delete', payload: { index: number }): void
  (e: 'duplicate', payload: { index: number }): void
}>()

const { t } = useI18n()

const modeType = computed(() => (props.step?.mode?.type ?? 'sequential') as string)

const localizedModeLabel = computed(() => {
  return modeOptionLabel(modeType.value)
})

function modeOptionLabel(type: string): string {
  const key = `workflows.canvas.modeLabels.${type}`
  const localized = t(key, '')
  return localized && localized !== key ? localized : type
}

const modeNeedsAgent = computed(() => {
  // fan_out / collect / await_approval / dispatch_channel / write_memory
  // don't take an agent — the runtime calls a service adapter instead.
  return ['sequential', 'conditional'].includes(modeType.value)
})

const rawJson = computed(() => {
  try { return JSON.stringify(props.step ?? {}, null, 2) } catch { return '' }
})

function modeField<T>(key: string, fallback: T): T {
  const v = props.step?.mode?.[key as keyof typeof props.step.mode]
  return (v ?? fallback) as T
}

function parseList(raw: string): string[] {
  return raw.split(',').map((s) => s.trim()).filter(Boolean)
}

function patch(p: Partial<RawStep>) {
  emit('patch', { index: props.index, patch: p })
}

function patchMode(modePatch: Record<string, unknown>) {
  emit('patch', {
    index: props.index,
    patch: { mode: { ...(props.step?.mode ?? {}), ...modePatch } as RawStep['mode'] },
  })
}

function onModeChange(e: Event) {
  const next = (e.target as HTMLSelectElement).value
  // Reset mode-only fields when the type changes — keep `type` as the
  // single carry-over so the schema validator doesn't complain about
  // stale fields like `expression` lingering on a sequential step.
  emit('patch', {
    index: props.index,
    patch: { mode: { type: next } as RawStep['mode'] },
  })
}

function onDelete() {
  emit('delete', { index: props.index })
}
function onDuplicate() {
  emit('duplicate', { index: props.index })
}

// Agent picker support — toggles between dropdown and free-text input.
const useFreeAgentName = ref(false)
const agentNotInList = computed(() => {
  const n = props.step?.agentName
  if (!n) return false
  return !props.availableAgents.some((a) => a.name === n)
})
const agentSelectValue = computed(() => props.step?.agentName ?? '')
function onAgentSelect(e: Event) {
  const v = (e.target as HTMLSelectElement).value
  if (v === '__custom__') {
    useFreeAgentName.value = true
    return
  }
  patch({ agentName: v })
}
</script>

<style scoped>
.step-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 10px 12px;
  background: var(--mc-bg-elevated, #ffffff);
  border: 1px solid var(--mc-border-light, rgba(0, 0, 0, 0.08));
  border-radius: 8px;
  color: var(--mc-text-primary, inherit);
  overflow-y: auto;
  font-size: 12.5px;
}
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  border-bottom: 1px solid var(--mc-border-light, rgba(0, 0, 0, 0.06));
  padding-bottom: 8px;
}
.panel-title {
  font-weight: 600;
  font-size: 13px;
}
.panel-actions {
  display: flex;
  gap: 4px;
}
.panel-btn {
  width: 26px;
  height: 26px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 5px;
  border: 1px solid var(--mc-border-light, rgba(0, 0, 0, 0.08));
  background: transparent;
  color: var(--mc-text-secondary, inherit);
  cursor: pointer;
}
.panel-btn:hover {
  background: var(--mc-bg-muted, rgba(0, 0, 0, 0.04));
  color: var(--mc-text-primary, inherit);
}
.panel-btn.danger:hover {
  color: var(--mc-danger, #c0392b);
  border-color: var(--mc-danger-border, rgba(231, 76, 60, 0.4));
}
.panel-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 0 0;
  border: none;
  margin: 0;
}
.panel-section legend {
  font-size: 10.5px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--mc-text-tertiary, #888);
  font-weight: 600;
  padding: 0;
  margin-bottom: 2px;
}
:lang(zh-CN) .panel-section legend {
  text-transform: none;
}
.panel-field {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 12px;
}
.panel-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.field-label {
  font-size: 10.5px;
  letter-spacing: 0.04em;
  color: var(--mc-text-tertiary, #888);
}
:lang(en) .field-label {
  text-transform: uppercase;
}
.mc-input,
.mc-textarea {
  padding: 6px 8px;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  border-radius: 5px;
  background: var(--mc-bg, transparent);
  color: inherit;
  font: inherit;
  font-size: 12.5px;
  outline: none;
  transition: border-color 0.12s ease;
  width: 100%;
}
.mc-input:focus,
.mc-textarea:focus {
  border-color: var(--mc-primary, #4084ff);
}
.mc-textarea {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11.5px;
  resize: vertical;
}
.mc-input.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11.5px;
}
.agent-picker {
  display: flex;
  align-items: center;
  gap: 4px;
}
.agent-picker .mc-input { flex: 1; min-width: 0; }
.agent-toggle {
  width: 24px;
  height: 28px;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  border-radius: 5px;
  background: transparent;
  color: var(--mc-text-tertiary, #888);
  cursor: pointer;
  font-size: 14px;
}
.agent-toggle:hover {
  background: var(--mc-bg-muted, rgba(0, 0, 0, 0.04));
  color: var(--mc-text-primary, inherit);
}
.panel-hint {
  font-size: 11.5px;
  color: var(--mc-text-tertiary, #888);
  font-style: italic;
  margin: 0;
}
.raw-section summary {
  font-size: 11px;
  color: var(--mc-text-tertiary, #888);
  cursor: pointer;
  padding: 4px 0;
}
.raw-json {
  margin: 4px 0 0;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 10.5px;
  background: var(--mc-bg-sunken, rgba(0, 0, 0, 0.04));
  border-radius: 4px;
  padding: 8px;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 220px;
  overflow: auto;
}
</style>
