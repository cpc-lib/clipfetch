#请使用管理员进行运行
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
