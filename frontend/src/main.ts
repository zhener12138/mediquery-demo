import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import App from './App.vue'
import router from './router'
import './assets/main.css'

const app = createApp(App)

// 这里原来是一段全量注册图标组件的循环：
//
//   import * as ElementPlusIconsVue from '@element-plus/icons-vue'
//   for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
//     app.component(key, component)
//   }
//
// 它把 Element Plus 的约 300 个图标全部注册成全局组件。问题是：
// 1. 全项目用到的图标不到十个（FirstAidKit / ChatDotRound / Document / WarningFilled
//    等），而且全都是**在使用处 import 后直接绑定**，从来不依赖全局注册；
// 2. 全量注册会让每个图标都进主包，形成"每次有人加了一个图标，包就大一点"的单向增长；
// 3. 更要紧的是它挡住了 tree-shaking——import * 本身就无法摇掉。
//
// 所以直接删掉。以后要用新图标，在对应 .vue 文件里 import 即可，
// **不要**再把全局注册加回来。

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
