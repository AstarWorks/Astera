<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <div class="root">
    <header class="topnav">
      <div class="brand">Astera Studio</div>
      <nav class="tabs">
        <RouterLink to="/textures" class="tab">🎨 テクスチャ</RouterLink>
        <RouterLink to="/models" class="tab">🧊 3D モデル</RouterLink>
        <RouterLink to="/effects" class="tab">✨ エフェクト</RouterLink>
        <RouterLink to="/weapons" class="tab">⚔️ ウェポン</RouterLink>
      </nav>
      <div class="spacer"></div>
    </header>
    <main class="view">
      <RouterView />
    </main>
  </div>
</template>

<style>
* {
  box-sizing: border-box;
}
html, body, #app {
  margin: 0;
  height: 100%;
  font-family: ui-sans-serif, system-ui, -apple-system, sans-serif;
  background: #0e0e10;
  color: #eee;
  font-size: 14px;
}
button {
  font-family: inherit;
}
.root {
  display: flex;
  flex-direction: column;
  height: 100vh;
  min-height: 0;
}
.topnav {
  display: flex;
  align-items: center;
  gap: 16px;
  background: #18181b;
  border-bottom: 1px solid #2a2a2f;
  padding: 0 16px;
  height: 44px;
  flex: 0 0 auto;
}
.brand {
  font-weight: 600;
  font-size: 14px;
  color: #eee;
}
.tabs {
  display: flex;
  gap: 4px;
}
.tab {
  text-decoration: none;
  color: #aaa;
  padding: 8px 14px;
  border-radius: 4px;
  font-size: 13px;
  transition: background 0.1s, color 0.1s;
}
.tab:hover {
  background: #26262b;
  color: #eee;
}
.tab.router-link-active {
  background: #3b82f6;
  color: white;
  font-weight: 600;
}
.spacer {
  flex: 1;
}
.view {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
</style>
