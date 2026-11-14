# 内部视频通话（Go 服务端 + Web + Android）

一对一视频通话：服务端机器上的浏览器是一方，Android App 是另一方。由 Android 发起呼叫，Web 端接听。

```
 Android (可在 SNAT 内网)                    服务端机器（公网 IP / 端口转发）
┌──────────────┐  主动外连 TCP(+UDP) :PORT  ┌──────────────────────────────────────┐
│ App           │ ─────────────────────────▶ │ videocall-server（单端口）           │
│ 摄像头/麦克风 │  ① WSS 信令（TLS，证书固定）│   ├─ HTTPS/WSS: Web 界面 + 信令      │
└──────────────┘  ② TURN 中继               │   ├─ HTTP: 仅限本机回环访问           │
        ▲                                   │   └─ TURN: 只搬运密文，中继在 127.0.0.1│
        ╚═══════ DTLS-SRTP 端到端加密 ═══════╗│                ▲ 本机回环            │
                                            ╚│ 浏览器（Web 端，通话另一方）           │
                                             └──────────────────────────────────────┘
```

## 协议与安全设计

| 层 | 协议 | 说明 |
|---|---|---|
| 信令 | WebSocket over TLS 1.2/1.3 | 认证、呼叫控制、交换 SDP/ICE |
| 连通性 | ICE + 内嵌 TURN（RFC 8656），UDP 优先、TCP 兜底 | Android 只需能外连服务端端口 |
| 媒体 | DTLS-SRTP（WebRTC 强制） | 浏览器 ↔ Android 端到端加密，服务端只转发密文 |

- **单端口复用**：TCP 端口按首字节分流：`0x16` 是 TLS（HTTPS/WSS），`0x00`/`0x01` 是 STUN/TURN，ASCII 是明文 HTTP（只服务本机回环，其它来源重定向到 HTTPS）。同号 UDP 端口跑 TURN-UDP。
- **证书**：默认自动生成自签名 ECDSA P-256 证书（存放在数据目录），控制台和 Web 页面会显示 SHA-256 指纹。Android 首次连接时弹出指纹让你核对，之后固定信任该证书（TOFU），指纹一变就拦截并告警。也可以用 `-cert/-key` 指定 CA 签发的证书，这时 App 按系统 CA 和主机名正常校验。
- **为什么证书固定很重要**：DTLS 指纹写在 SDP 里，而 SDP 走 WSS 传输。WSS 证书核对通过，中间人就无法替换 DTLS 指纹，端到端加密才成立。
- **密码**：可选。比较时先做 SHA-256 再用常量时间比较；同一 IP 连续失败 5 次锁定，锁定时长从 1 分钟起翻倍，最长 30 分钟。Web 端和 Android 端使用同一个密码。
- **TURN 加固**：TURN 凭据是认证通过后才下发的短期 HMAC 凭据，有效期 24 小时，每次启动随机生成密钥。中继 socket 只绑定在 `127.0.0.1`，只允许发往本服务自己的其它中继分配地址，不能被当作跳板访问服务端本机端口或内网（参见 RFC 8656 §21）。TCP 中继（RFC 6062）已禁用。
- **HTTP 安全头**：严格的 CSP，并设置 `frame-ancestors 'none'`、`nosniff`、`no-referrer`。

## 服务端

### 运行

从 Release 下载对应平台的包：

- macOS：`videocall-server-<ver>-darwin-arm64.tar.gz`（Apple 芯片）或 `-darwin-amd64.tar.gz`（Intel）
- Windows：`videocall-server-<ver>-windows-amd64.zip`

```bash
# macOS：二进制未经 Apple 公证，首次运行前需要去掉隔离属性
xattr -d com.apple.quarantine ./videocall-server
./videocall-server -password '你的密码'
```

Windows 直接双击 `videocall-server.exe` 即可，也可以在命令行带参数运行。

启动后会自动打开 `http://localhost:8443/`。这是本机安全上下文，没有证书警告。

### 配置

首次运行会在数据目录生成 `config.json`，以后直接双击启动也会读取它。数据目录默认是：macOS `~/Library/Application Support/videocall-server`，Windows `%AppData%\videocall-server`。

```json
{
  "listen": ":8443",
  "password": "",
  "cert_file": "",
  "key_file": "",
  "hosts": [],
  "disable_udp": false,
  "open_browser": true
}
```

命令行参数优先级高于环境变量，环境变量高于 `config.json`：

| 参数 | 说明 |
|---|---|
| `-listen :8443` | 监听地址，TCP 和 UDP 使用同一个端口 |
| `-password xxx` | 访问密码；也可以用环境变量 `VIDEOCALL_PASSWORD`，这样密码不会出现在进程列表里 |
| `-cert/-key` | 使用自己的证书，例如 Let's Encrypt |
| `-hosts a.example.com,1.2.3.4` | 自签名证书额外加入的 SAN；只在首次生成证书时生效 |
| `-no-udp` | 只用 TCP 中继 |
| `-open=false` | 启动时不自动打开浏览器 |
| `-data DIR` | 数据目录 |

想重新生成自签名证书，删除数据目录里的 `cert.pem` 和 `key.pem` 再重启即可。注意：之后 Android 端会提示证书已变化，需要重新核对指纹。

### 网络要求

- 路由器或防火墙需要把 **TCP `PORT`** 转发到服务端（必需），**UDP `PORT`** 建议也转发；UDP 不通时自动退到 TCP。
- 外部端口和内部端口可以不同（例如外部 443 转到内部 8443）。服务端会根据客户端实际连接的 `Host:端口` 下发 TURN 地址，但 TCP 和 UDP 的端口映射必须一致。
- Chrome 不允许把 TURN 服务器放在 1024 以下的端口（53/80/443 除外），所以外部端口请用 443 或大于等于 1024 的端口。
- 不支持放在 HTTP 反向代理后面，因为 TURN 流量无法经过 HTTP 代理。

## Android 客户端

- 最低支持 Android 7.0（API 24），安装 Release 中的 `videocall-<ver>.apk`。
- 输入服务端 IP 或域名、端口、密码后点“呼叫”。首次连接需要核对证书指纹。
- 通话中可以静音、前后摄像头切换、开关摄像头、挂断。
- “记住密码”会用 Android Keystore 中的 AES-GCM 密钥加密保存。

### 签名

`android/app/release.keystore` 和 `keystore.properties` 按需求直接提交在仓库里，本地构建和 CI 构建使用同一把签名密钥，所以升级时可以直接覆盖安装。需要注意的风险：任何能读这个仓库的人都能用这个身份给 APK 签名。如果密钥泄露或仓库可见范围变了，就换一把新密钥；换密钥后，已安装的旧版需要卸载重装。

## 构建与发版

```bash
cd server && go test ./... && go build -o videocall-server .
```

```bash
cd android && ./gradlew assembleRelease
```

发版方式：推送 `v*` 标签，或在 Actions 里手动运行 **Release** 工作流并填写版本号。工作流会构建 macOS arm64/amd64 和 Windows amd64 的服务端，以及签名 APK，附上 `SHA256SUMS.txt` 后发布到 GitHub Release。

```bash
git tag v0.1.0
```

```bash
git push origin v0.1.0
```

## 已知限制

- 只支持一对一通话，Web 端和 Android 端同一时间各只能在线一个。
- Android 端在后台时，系统会限制摄像头和麦克风，通话需要保持在前台（屏幕常亮已开启）。
- 服务端二进制没有做 Apple 签名和公证，也没有做 Windows Authenticode 签名。
- 从其它机器用 HTTPS 打开 Web 界面时，浏览器会对自签名证书给出一次警告。
