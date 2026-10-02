import { reactive } from 'vue'

/** Cookies 管理弹窗状态（Header 入口 / 失效引导共用） */
export const cookieModal = reactive({
  visible: false,
  open() {
    this.visible = true
  },
  close() {
    this.visible = false
  }
})
