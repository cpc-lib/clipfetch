# 直接在普通 PowerShell 中运行即可。注册表策略一般不需要管理员权限；如果
# HKCU\Software\Policies 被加固成只读，脚本会自动请求 UAC 提权并以管理员重跑；
# 创建防火墙规则也一定会弹出 UAC 提权窗口。取消提权只会给出明确提示，不会报错。
#  解除下载文件的安全阻止标记
Unblock-File -LiteralPath "D:\scripts\set-webrtc-protection\set-webrtc-protection.ps1"

# 允许当前用户执行本地脚本
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned -Force

# 安装保护
& "D:\scripts\set-webrtc-protection\set-webrtc-protection.ps1" -Action Install

检查状态
& "D:\scripts\set-webrtc-protection\set-webrtc-protection.ps1" -Action Check

#移除
& "D:\scripts\set-webrtc-protection\set-webrtc-protection.ps1" -Action Remove
