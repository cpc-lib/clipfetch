#请使用管理员进行运行
#  解除下载文件的安全阻止标记
Unblock-File -LiteralPath "D:\scripts\node-proxy.ps1"

# 允许当前用户执行本地脚本
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned -Force

# 启用并永久保存代理
& "D:\scripts\node-proxy.ps1"

# 查看代理状态
& "D:\scripts\node-proxy.ps1" -Mode Status

# 关闭代理（需要时执行）
# & "D:\scripts\node-proxy.ps1" -Mode Disable