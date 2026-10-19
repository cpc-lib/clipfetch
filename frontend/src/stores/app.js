import { reactive } from 'vue'
import router from '../router'

/**
 * 全局主视图状态与路由同步器：
 * 保证既有代码调 appView.set(...) 与 appView.goParse(...) 能够驱动路由跳转，
 * 同时路由变化时自动保持 active 字段一致。
 */
export const appView = reactive({
  active: 'video',
  /** 从文件库/预解析库跳转解析时预填的链接 */
  prefillUrl: '',
  set(view) {
    this.active = view
    const pathMap = {
      video: '/',
      subtitle: '/subtitle',
      library: '/library',
      preparse: '/preparse',
      magnet: '/magnet',
      native: '/magnet/native'
    }
    const target = pathMap[view] || '/'
    if (router.currentRoute.value.path !== target && router.currentRoute.value.fullPath !== target) {
      router.push(target)
    }
  },
  /** 跳转到视频解析视图并预填链接 */
  goParse(url) {
    this.prefillUrl = url || ''
    this.active = 'video'
    if (router.currentRoute.value.path !== '/') {
      router.push('/')
    }
  }
})

// 监听路由变化，自动同步 appView.active 状态
router.afterEach((to) => {
  const p = to.path
  if (p === '/') {
    appView.active = 'video'
  } else if (p.startsWith('/subtitle')) {
    appView.active = 'subtitle'
  } else if (p.startsWith('/library')) {
    appView.active = 'library'
  } else if (p.startsWith('/preparse')) {
    appView.active = 'preparse'
  } else if (p.startsWith('/magnet')) {
    appView.active = to.params.engine === 'native' || to.query.engine === 'native' ? 'native' : 'magnet'
  }
})
