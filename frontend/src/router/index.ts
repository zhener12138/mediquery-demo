import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: () => import('@/pages/public/HomePage.vue') },
    { path: '/login', name: 'login', component: () => import('@/pages/public/LoginPage.vue') },
    { path: '/register', name: 'register', component: () => import('@/pages/public/RegisterPage.vue') },
    { path: '/search', name: 'search', component: () => import('@/pages/public/SearchPage.vue') },
    { path: '/illness/:id', name: 'illness-detail', component: () => import('@/pages/public/IllnessDetail.vue') },
    { path: '/medicine/:id', name: 'medicine-detail', component: () => import('@/pages/public/MedicineDetail.vue') },
    { path: '/illnesses', name: 'illnesses', component: () => import('@/pages/public/IllnessList.vue') },
    { path: '/medicines', name: 'medicines', component: () => import('@/pages/public/MedicineList.vue') },
    { path: '/doctors', name: 'doctors', component: () => import('@/pages/public/DoctorList.vue') },
    { path: '/doctor/:id', name: 'doctor-detail', component: () => import('@/pages/public/DoctorDetail.vue') },
    { path: '/ai-doctor', name: 'ai-doctor', meta: { requiresAuth: true }, component: () => import('@/pages/user/AiDoctorPage.vue') },
    { path: '/profile', name: 'profile', meta: { requiresAuth: true }, component: () => import('@/pages/user/ProfilePage.vue') },
    { path: '/feedback', name: 'feedback', meta: { requiresAuth: true }, component: () => import('@/pages/user/FeedbackPage.vue') },
    { path: '/admin/dashboard', name: 'dashboard', meta: { requiresAuth: true, requiresAdmin: true }, component: () => import('@/pages/admin/DashboardPage.vue') },
    { path: '/admin/illnesses', name: 'manage-illnesses', meta: { requiresAuth: true, requiresAdmin: true }, component: () => import('@/pages/admin/IllnessManage.vue') },
    { path: '/admin/medicines', name: 'manage-medicines', meta: { requiresAuth: true, requiresAdmin: true }, component: () => import('@/pages/admin/MedicineManage.vue') },
    { path: '/admin/doctors', name: 'manage-doctors', meta: { requiresAuth: true, requiresAdmin: true }, component: () => import('@/pages/admin/DoctorManage.vue') },
    { path: '/admin/feedbacks', name: 'manage-feedbacks', meta: { requiresAuth: true, requiresAdmin: true }, component: () => import('@/pages/admin/FeedbackManage.vue') },
    { path: '/admin/users', name: 'manage-users', meta: { requiresAuth: true, requiresAdmin: true }, component: () => import('@/pages/admin/UserManage.vue') },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/pages/public/NotFound.vue') },
  ],
  scrollBehavior(to, from, savedPosition) {
    return savedPosition || { top: 0 }
  }
})

router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('accessToken')
  const userInfo = JSON.parse(localStorage.getItem('userInfo') || '{}')

  if (to.meta.requiresAuth && !token) {
    next({ name: 'login', query: { redirect: to.fullPath } })
    return
  }
  if (to.meta.requiresAdmin && userInfo.roleStatus !== 1) {
    next({ name: 'home' })
    return
  }
  next()
})

export default router
