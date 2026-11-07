import { createRouter, createWebHistory } from 'vue-router'

/**
 * 路由表。
 *
 * <h3>为什么只剩这几条</h3>
 *
 * <p>这个项目现在只做一件事：在线问诊。原来那二十几条路由里，
 * 疾病百科、药品查询、找医生、意见反馈、数据大屏、五个管理后台页面，
 * 全部删掉了 —— 它们占着大部分路由表，却和「问诊」这个主线没有因果关系。
 *
 * <p>知识库（疾病/药品数据）没有被删，它退到了后端，变成 RAG 检索的底座。
 * 用户不需要「查」它，它自己会在对话里出现。这才是它该有的位置。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: () => import('@/pages/public/HomePage.vue') },
    { path: '/login', name: 'login', component: () => import('@/pages/public/LoginPage.vue') },
    { path: '/register', name: 'register', component: () => import('@/pages/public/RegisterPage.vue') },
    {
      path: '/consultation/:id?',
      name: 'consultation',
      meta: { requiresAuth: true },
      component: () => import('@/pages/user/ConsultationPage.vue'),
    },
    {
      path: '/profile',
      name: 'profile',
      meta: { requiresAuth: true },
      component: () => import('@/pages/user/ProfilePage.vue'),
    },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/pages/public/NotFound.vue') },
  ],
  scrollBehavior(to, from, savedPosition) {
    return savedPosition || { top: 0 }
  },
})

router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('accessToken')

  if (to.meta.requiresAuth && !token) {
    next({ name: 'login', query: { redirect: to.fullPath } })
    return
  }
  next()
})

export default router
