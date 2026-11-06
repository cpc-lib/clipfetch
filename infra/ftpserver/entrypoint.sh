#!/usr/bin/env bash
set -euo pipefail

FTP_USER="${FTP_USER:-ftpuser}"
FTP_PASS="${FTP_PASS:-ftppass123}"
PASV_ADDRESS="${PASV_ADDRESS:-127.0.0.1}"
PASV_MIN_PORT="${PASV_MIN_PORT:-30000}"
PASV_MAX_PORT="${PASV_MAX_PORT:-30009}"
SSL_ENABLE="${SSL_ENABLE:-NO}"
ANON_ENABLE="${ANON_ENABLE:-NO}"

SSL_DIR=/etc/vsftpd/ssl
CONF=/etc/proftpd-fixture.conf

# 自签证书（CN=localhost），已存在则复用，便于后端 truststore 测试固定信任
if [[ "${SSL_ENABLE}" == "YES" ]]; then
  mkdir -p "${SSL_DIR}"
  if [[ ! -s "${SSL_DIR}/vsftpd.pem" || ! -s "${SSL_DIR}/vsftpd.key" ]]; then
    openssl req -x509 -nodes -newkey rsa:2048 -days 3650 \
      -keyout "${SSL_DIR}/vsftpd.key" \
      -out "${SSL_DIR}/vsftpd.pem" \
      -subj "/CN=localhost" \
      -addext "subjectAltName=DNS:localhost,DNS:ftp,DNS:ftps,IP:127.0.0.1"
  fi
  chmod 600 "${SSL_DIR}/vsftpd.key"
fi

# 具名测试账号（每次启动重置密码，保证 compose 环境变量生效；允许在 /srv/ftp/pub 下建目录与上传）
if ! id "${FTP_USER}" >/dev/null 2>&1; then
  useradd -m -s /bin/bash "${FTP_USER}"
fi
echo "${FTP_USER}:${FTP_PASS}" | chpasswd
# 递归交给测试账号：镜像内预置文件与子目录均属 root，仅 chown 顶层会导致
# ftpuser 在 /subdir 等子目录建文件夹/上传时被服务端以 550 拒绝
chown -R "${FTP_USER}:nogroup" /srv/ftp/pub
chmod 775 /srv/ftp/pub

# 渲染被动模式配置
sed -e "s/@PASV_ADDRESS@/${PASV_ADDRESS}/g" \
    -e "s/@PASV_MIN_PORT@/${PASV_MIN_PORT}/g" \
    -e "s/@PASV_MAX_PORT@/${PASV_MAX_PORT}/g" \
    /etc/proftpd-fixture.conf.template > "${CONF}"

# 显式 FTPS：控制与数据通道均强制 TLS，禁止明文回退
if [[ "${SSL_ENABLE}" == "YES" ]]; then
  cat >> "${CONF}" <<EOF
LoadModule            mod_tls.c
<IfModule mod_tls.c>
  TLSEngine           on
  TLSRequired         on
  TLSProtocol         TLSv1.2 TLSv1.3
  TLSRSACertificateFile ${SSL_DIR}/vsftpd.pem
  TLSRSACertificateKeyFile ${SSL_DIR}/vsftpd.key
  TLSVerifyClient     off
  # 不要求数据连接复用控制连接 TLS 会话（curl/Commons Net 等客户端默认不复用）
  TLSOptions          NoSessionReuseRequired
</IfModule>
EOF
fi

# 匿名只读（明文 ftp 服务使用）
if [[ "${ANON_ENABLE}" == "YES" ]]; then
  cat >> "${CONF}" <<EOF
<Anonymous /srv/ftp/pub>
  User              ftp
  Group             nogroup
  RequireValidShell off
  UserAlias         anonymous ftp
  <Limit WRITE>
    DenyAll
  </Limit>
</Anonymous>
EOF
fi

mkdir -p /var/run/proftpd
exec /usr/sbin/proftpd -n -c "${CONF}"
