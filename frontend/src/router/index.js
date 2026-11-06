import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    name: 'video',
    component: () => import('../views/VideoView.vue'),
    meta: { title: '链接解析 - ClipFetch' }
  },
  {
    path: '/subtitle',
    name: 'subtitle',
    component: () => import('../components/SubtitleStudio.vue'),
    meta: { title: '字幕转换 - ClipFetch' }
  },
  {
    path: '/library',
    name: 'library',
    component: () => import('../components/MusicLibrary.vue'),
    meta: { title: '文件库 - ClipFetch' }
  },
  {
    path: '/preparse',
    name: 'preparse',
    component: () => import('../components/PreParseLibrary.vue'),
    meta: { title: '预解析库 - ClipFetch' }
  },
  {
    path: '/magnet/:engine?',
    name: 'magnet',
    component: () => import('../components/MagnetEngineWorkspace.vue'),
    meta: { title: '磁力下载 - ClipFetch' }
  },
  {
    path: '/ed2k',
    name: 'ed2k',
    component: () => import('../components/Ed2kDownloader.vue'),
    meta: { title: '电驴下载 - ClipFetch' }
  },
  {
    path: '/ftp',
    name: 'ftp',
    component: () => import('../components/FtpDownloader.vue'),
    meta: { title: 'FTP / FTPS 下载 - ClipFetch' }
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(to, from, savedPosition) {
    if (to.hash) {
      return { el: to.hash, behavior: 'smooth' }
    }
    if (savedPosition) {
      return savedPosition
    }
    return { top: 0 }
  }
})

router.afterEach((to) => {
  if (to.meta.title) document.title = to.meta.title
})

export default router
