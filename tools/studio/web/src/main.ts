import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'
import App from './App.vue'
import TexturesView from './routes/TexturesView.vue'
import ModelsView from './routes/ModelsView.vue'
import EffectsView from './routes/EffectsView.vue'
import ItemsView from './routes/ItemsView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/textures' },
    { path: '/textures', component: TexturesView },
    { path: '/models', component: ModelsView },
    { path: '/effects', component: EffectsView },
    { path: '/items', component: ItemsView },
  ],
})

createApp(App).use(router).mount('#app')
