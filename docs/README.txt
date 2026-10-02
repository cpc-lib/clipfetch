====================================================
 ClipFetch 部署说明
====================================================

一、项目简介
----------------------------------------------------
ClipFetch 是一个视频解析/下载工具，本目录为其 Windows 部署包：

  app.jar      后端服务（Spring Boot，视频解析、下载、用户/鉴权等）
  dist/        前端静态页面（Vue 打包产物）
  nginx/       Nginx（托管前端页面，并反向代理后端 API / WebSocket）
  .env         运行配置（数据库、密钥、工具路径等）
  start.bat    后端一键启动脚本

二、环境要求
----------------------------------------------------
  - JDK 21（JAVA_HOME 或 PATH 中有 java）
  - yt-dlp     视频解析核心工具
  - ffmpeg     音视频合并
  - deno       YouTube PO token / BotGuard 挑战（仅解析 YouTube 需要）
  - aria2c     多线程下载加速（可选）
  - Node.js    CCTV h5e 解密（仅解析 CCTV 需要）

三、启动方式
----------------------------------------------------
1. 启动后端：双击运行 start.bat
   （脚本自动读取 .env，默认端口见 SERVER_PORT，当前为 8081）

2. 启动前端（Nginx）：进入 nginx 目录执行
     nginx.exe
   停止：nginx.exe -s stop
   修改配置后重载：nginx.exe -s reload

3. 浏览器访问：http://localhost/
   Nginx 将 /api/ 与 /ws 请求转发到后端 127.0.0.1:8081。

四、配置说明（.env）
----------------------------------------------------
  DB_URL                数据库连接。默认嵌入式 SQLite：
                        jdbc:sqlite:D:/clipfetch/sqlite/fvd.db
                        注意：SQLite 不会自动创建父目录，
                        请确保 D:/clipfetch/sqlite 目录已存在。
  DB_DRIVER/DB_USER/DB_PASSWORD
                        切换 MySQL 时取消注释对应配置（.env 优先于内置默认值）
  JWT_SECRET            登录令牌密钥，生产环境务必改为随机长字符串
  SERVER_PORT           后端端口（需与 nginx.conf 中 proxy_pass 端口一致）
  YTDLP_PATH / FFMPEG_LOCATION / JS_RUNTIME_PATH / ARIA2C_PATH
                        各工具路径，支持 %VAR% 引用系统环境变量
  PROXY_URL             出站代理（访问 YouTube/Twitter 需要）
  DOWNLOADS_DIR         临时下载目录（相对路径以后端工作目录为基准）

五、常见问题
----------------------------------------------------
Q: 修改 DB_URL 后数据库没有生效/启动报错？
A: SQLite 不会自动创建父目录。若配置为
   jdbc:sqlite:D:/clipfetch/sqlite/fvd.db，请先手动创建
   D:\clipfetch\sqlite 文件夹，再重启后端。

Q: 页面能打开但接口 502？
A: 后端未启动，或 .env 中 SERVER_PORT 与
   nginx/conf/nginx.conf 里 proxy_pass 的端口（8081）不一致。

Q: YouTube 解析失败？
A: 检查 PROXY_URL 代理是否可用、deno 路径是否正确。

Q: 下载慢？
A: 确认 aria2c 路径配置正确，ARIA2C_CONNECTIONS 默认为 16。
