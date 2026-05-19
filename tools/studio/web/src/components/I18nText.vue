<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from '../lib/i18n'

const props = defineProps<{
  /** Translation key, e.g. `astera.weapon.example-sword.name`. */
  keyName: string
  /** Optional explicit locale; defaults to the current app-wide locale. */
  locale?: string
}>()

const { resolve, currentLocale } = useI18n()

const resolved = computed(() => resolve(props.keyName, props.locale ?? currentLocale.value))
const isMissing = computed(() => resolved.value === null)
</script>

<template>
  <span class="i18n-text" :class="{ 'i18n-missing': isMissing }">
    <template v-if="isMissing">
      <code>{{ keyName }}</code>
      <span class="i18n-warning" :title="`Translation missing for locale '${locale ?? currentLocale}'`">?</span>
    </template>
    <template v-else-if="Array.isArray(resolved)">
      <span v-for="(line, i) in resolved" :key="i" class="i18n-line">{{ line }}</span>
    </template>
    <template v-else>{{ resolved }}</template>
  </span>
</template>

<style scoped>
.i18n-text {
  display: inline;
  color: inherit;
}
.i18n-text .i18n-line {
  display: block;
}
.i18n-missing code {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 0.9em;
  background: #18181b;
  color: #d1d5db;
  padding: 1px 4px;
  border-radius: 2px;
}
.i18n-warning {
  display: inline-block;
  margin-left: 4px;
  width: 14px;
  height: 14px;
  line-height: 14px;
  text-align: center;
  background: #7f1d1d;
  color: #fee2e2;
  border-radius: 50%;
  font-size: 10px;
  font-weight: 700;
  cursor: help;
}
</style>
