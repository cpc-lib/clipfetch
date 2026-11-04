# Debian bookworm 原生依赖构建 C++ gRPC Sidecar（单文件、无 Docker BuildKit 特性）。
# 本文件不依赖 BuildKit secret、多阶段构建等复杂特性，保证 Docker 29.x CLI 也能跑。
FROM debian:bookworm-slim

# 无交互 + 预接受 libtorrent-rasterbar 可能的 apt key（如果被移到 backports）
ENV DEBIAN_FRONTEND=noninteractive

# 工具链 + C++ 依赖（Debian bookworm 原生包）
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    build-essential \
    cmake \
    ninja-build \
    pkg-config \
    libtorrent-rasterbar-dev \
    protobuf-compiler \
    protobuf-compiler-grpc \
    libprotobuf-dev \
    libprotoc-dev \
    libgrpc++-dev \
    libgrpc-dev \
    && rm -rf /var/lib/apt/lists/*

# 工作目录（与 host 挂载点保持一致）
WORKDIR /workspace

# 复制 proto + worker 源码
COPY proto/ /workspace/proto/
COPY libtorrent-worker/ /workspace/libtorrent-worker/

# 构建
RUN cmake -S /workspace/libtorrent-worker \
          -B /workspace/build \
          -G Ninja \
          -DCMAKE_BUILD_TYPE=Release \
    && cmake --build /workspace/build -j "$(nproc)"

EXPOSE 50051

ENV WORKER_ID=local-worker-1
ENV DOWNLOAD_ROOT=/data
ENV LOCK_ROOT=/locks

VOLUME ["/data", "/locks"]

# 默认监听全网卡，端口 50051；health 就绪探针可通
CMD ["/workspace/build/libtorrent-worker", "--port=50051", "--download-root=/data"]
