import { reactive } from 'vue'

/**
 * 全局主视图切换（无路由单页）：
 *  - video：视频解析下载（默认）
 *  - subtitle：字幕转换工作室
 *  - library：网易云音乐文件库
 *  - preparse：预解析库
 */
export const appView = reactive({
  active: 'video',
  /** 从文件库/预解析库跳转解析时预填的链接 */
  prefillUrl: '',
  set(view) {
    this.active = view
  },
  /** 跳转到视频解析视图并预填链接 */
  goParse(url) {
    this.prefillUrl = url || ''
    this.active = 'video'
  }
})
