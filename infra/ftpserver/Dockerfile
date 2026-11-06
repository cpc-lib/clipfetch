# ProFTPD(mod_tls) 统一承载明文 FTP 与显式 FTPS 两个测试服务：
# 实测 vsftpd 3.0.3/3.0.5 在 OpenSSL 3.x 下快速连续显式 FTPS 连接会导致 master
# 进程 SIGSEGV（core dump 证实，详见 HANDOFF.md），故两个服务统一使用 ProFTPD。
FROM debian:trixie-slim

RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      proftpd-core proftpd-mod-crypto openssl \
 && rm -rf /var/lib/apt/lists/* \
 && (id ftp >/dev/null 2>&1 || useradd -r -d /srv/ftp -s /usr/sbin/nologin ftp)

# 固定只读测试数据：
#  - zero.bin              0 字节
#  - hello.txt             小文本
#  - file with spaces.txt  带空格文件名
#  - subdir/nested.txt     子目录路径
#  - random-1mib.bin       1 MiB 随机数据
#  - large-16mib.bin       16 MiB 随机数据（断点续传/完整性校验用）
#  - SHA256SUMS            上述文件摘要，供 expectedSha256 校验对照
RUN set -eux; \
    mkdir -p /srv/ftp/pub/subdir; \
    : > /srv/ftp/pub/zero.bin; \
    printf 'Hello from ClipFetch FTP fixture.\n' > /srv/ftp/pub/hello.txt; \
    printf 'name with spaces\n' > "/srv/ftp/pub/file with spaces.txt"; \
    printf 'nested file\n' > /srv/ftp/pub/subdir/nested.txt; \
    dd if=/dev/urandom of=/srv/ftp/pub/random-1mib.bin bs=1M count=1; \
    dd if=/dev/urandom of=/srv/ftp/pub/large-16mib.bin bs=1M count=16; \
    cd /srv/ftp/pub && sha256sum zero.bin hello.txt "file with spaces.txt" subdir/nested.txt random-1mib.bin large-16mib.bin > SHA256SUMS; \
    chown -R root:root /srv/ftp && chmod -R a+rX /srv/ftp

COPY proftpd.conf /etc/proftpd-fixture.conf.template
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
# Windows 编辑器可能写入 CRLF，构建期统一归一为 LF，避免 shebang/配置解析失败
RUN sed -i 's/\r$//' /etc/proftpd-fixture.conf.template /usr/local/bin/entrypoint.sh \
 && chmod +x /usr/local/bin/entrypoint.sh

EXPOSE 21
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
