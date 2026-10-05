import { reactive } from 'vue'

/**
 * 全局主视图切换（无路由单页）：
 *  - video：视频解析下载（默认）
 *  - subtitle：字幕转换工作室
 */
export const appView = reactive({
  active: 'video',
  set(view) {
    this.active = view
  }
})
