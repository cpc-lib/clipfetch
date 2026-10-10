#  解除下载文件的安全阻止标记
Unblock-File -LiteralPath "D:\scripts\node-proxy.ps1"

# 允许当前用户执行本地脚本
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned -Force

# 启用并永久保存代理(修改为目标node-proxy.ps1实际存放路径)
& "D:\scripts\node-proxy.ps1"

# 查看代理状态(c)
& "D:\scripts\node-proxy.ps1" -Mode Status

# 关闭代理(修改为目标node-proxy.ps1实际存放路径,需要时执行)
# & "D:\scripts\node-proxy.ps1" -Mode Disable