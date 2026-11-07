import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({
      resolvers: [ElementPlusResolver()],
    }),
    Components({
      resolvers: [ElementPlusResolver()],
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  build: {
    // 手动分包。默认策略把所有 node_modules 打成一个巨型 index chunk——
    // 实测改之前 index 是 1215KB，用户首屏必须下完整个 Element Plus 才能看到页面。
    //
    // 拆成两块，理由各不相同：
    //   vue           —— 版本最稳，缓存命中率最高；改业务代码它不用重下
    //   element-plus  —— 大但变动少，单独成块，业务迭代时不会带着它一起失效
    //
    // （原来还有第三块 echarts —— 那是给已经删掉的数据大屏用的，依赖一起清了。）
    //
    // 拆完别指望总字节变小（总量基本不变），要的是"改一行业务代码后
    // 用户不用重下 1MB"。这是缓存收益，不是体积收益——两者的价值不一样。
    rollupOptions: {
      output: {
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia'],
          'element-plus': ['element-plus', '@element-plus/icons-vue'],
        },
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        // 后端地址可用环境变量覆盖：本机 8080 常被上一次没退干净的实例占着，
        // 换端口起新实例时只要 VITE_API_TARGET=http://127.0.0.1:8090 即可，不用改文件。
        target: process.env.VITE_API_TARGET || 'http://localhost:8080',
        changeOrigin: true,
        // SSE 流式转发：避免代理缓冲 event-stream 响应
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes, req, res) => {
            if (proxyRes.headers['content-type']?.includes('text/event-stream')) {
              res.flushHeaders()
            }
          })
        },
      }
    }
  }
})
