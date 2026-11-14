# 内部视频通话（Go 服务端 + Web 控制台 + Android）

一对一视频通话：服务端机器上的浏览器（Web 控制台）是一方，Android App 是另一方。Android 发起呼叫，Web 端接听。

```
 Android (可在 SNAT 内网)                     服务端机器（公网 IP / 端口转发）
┌──────────────┐  主动外连 TCP(+UDP) :8443   ┌───────────────────────────────────────┐
│ App           │ ──────────────────────────▶ │ 通话端口 :8443（对外）                  │
│ 摄像头/麦克风 │  ① WSS 信令（TLS，证书固定） │   ├─ TLS: Android 信令 + 接入密码认证  │
└──────────────┘  ② TURN 中继               │   └─ TURN: 只搬运密文，中继在本机网卡 IP │
        ▲                                    │                                       │
        ║                                    │ 控制台端口 127.0.0.1:8080（HTTP）       │
        ╚════ DTLS-SRTP 端到端加密 ══════════▶│   └─ 浏览器 = 通话另一方 + 管理界面     │
                                             └───────────────────────────────────────┘
```

## 协议与安全设计

| 层 | 协议 | 说明 |
|---|---|---|
| 信令 | WebSocket over TLS 1.2/1.3（通话端口） | 认证、呼叫控制、交换 SDP/ICE |
| 连通性 | ICE + 内嵌 TURN（RFC 8656），UDP 优先、TCP 兜底 | Android 只需能外连通话端口 |
| 媒体 | DTLS-SRTP（WebRTC 强制） | 浏览器 ↔ Android 端到端加密，服务端只转发密文 |

- **双端口，按端口区分角色**
  - 通话端口（默认 `:8443`）：只接受 Android 身份，必须通过接入密码认证。TCP 上按首字节分流：`0x16` 走 TLS 信令，`0x00`/`0x01` 走 TURN，其它连接直接关闭。同号的 UDP 端口跑 TURN-UDP。
  - 控制台端口（默认 `127.0.0.1:8080`，HTTP）：Web 控制台，不需要登录。应用本身不做访问控制，由绑定地址、主机防火墙或反向代理（如 nginx）负责。注意：浏览器只把 `http://localhost` 视为安全上下文，只有在这种情况下才能调用摄像头。
- **接入密码**：在 Web 控制台设置、修改或清除。新连接立即使用新密码，进行中的通话不受影响。
  - 只把 argon2id 加盐哈希写入 `config.json`，参数为 m=19 MiB、t=2、p=1，即 OWASP Password Storage Cheat Sheet 给出的最低推荐配置。
  - 同一 IP 连续失败 5 次会被锁定，锁定时长从 1 分钟起逐次翻倍，最长 30 分钟。
- **证书**：默认自动生成自签名 ECDSA P-256 证书。Android 首次连接时显示 SHA-256 指纹供核对，确认后固定信任该证书（TOFU），以后指纹变化就拦截并告警。也可以用 `-cert/-key` 指定 CA 签发的证书，此时 App 按系统 CA 和主机名校验。
- **为什么证书固定很重要**：DTLS 指纹写在 SDP 里，而 SDP 走 WSS 传输。WSS 证书核对通过，中间人就无法替换 DTLS 指纹。
- **TURN 加固**
  - TURN 凭据是认证通过后才下发的短期 HMAC 凭据，有效期 24 小时，每次启动随机生成密钥。
  - 中继 socket 绑定在本机网卡地址（默认路由所在网卡），不额外开放端口；只允许发往本服务自己的其它中继地址，不能被当作跳板访问本机其它端口或内网（参见 RFC 8656 §21）。不使用回环地址，是因为部分 WebRTC 实现会忽略回环地址的 ICE 候选。
  - 不支持 TCP 中继分配（RFC 6062）。

## 服务端

### 运行

从 Release 下载对应平台的包：

- macOS：`videocall-server-<ver>-darwin-arm64.tar.gz`（Apple 芯片）或 `-darwin-amd64.tar.gz`（Intel）
- Windows：`videocall-server-<ver>-windows-amd64.zip`

```bash
# macOS：二进制未经 Apple 公证，首次运行前去掉隔离属性
xattr -d com.apple.quarantine ./videocall-server
./videocall-server
```

Windows 直接双击 `videocall-server.exe` 即可。

启动后会自动打开 `http://localhost:8080/`。首次启动时，在页面右侧设置 **Android 接入密码**。

### 配置

首次运行会在数据目录生成 `config.json`，以后双击启动也会读取它。数据目录默认是：macOS `~/Library/Application Support/videocall-server`，Windows `%AppData%\videocall-server`。

```json
{
  "listen": ":8443",
  "console_listen": "127.0.0.1:8080",
  "password_hash": "",
  "cert_file": "",
  "key_file": "",
  "hosts": null,
  "disable_udp": false,
  "open_browser": true
}
```

命令行参数只对本次运行生效，不会写回 `config.json`：

| 参数 | 说明 |
|---|---|
| `-listen :8443` | 通话端口，TCP 和 UDP 使用同一个端口号 |
| `-console 127.0.0.1:8080` | 控制台监听地址 |
| `-password xxx` | 本次运行使用的接入密码（也可以用环境变量 `VIDEOCALL_PASSWORD`）。日常请在控制台里设置 |
| `-cert/-key` | 使用自己的证书 |
| `-hosts a.example.com,1.2.3.4` | 自签名证书额外加入的 SAN，只在首次生成证书时生效 |
| `-no-udp` | 只用 TCP 中继 |
| `-open=false` | 启动时不自动打开浏览器 |
| `-data DIR` | 数据目录 |

想重新生成证书，删除数据目录中的 `cert.pem` 和 `key.pem` 再重启即可。之后 Android 会提示"证书已变化"，需要重新核对指纹。

### 网络要求

- 路由器或防火墙需要把通话端口的 **TCP** 转发到服务端（必需），**UDP** 建议也转发；UDP 不通时自动退到 TCP。外部端口可以和内部端口不同，但 TCP 和 UDP 的映射必须一致。
- Chrome 不允许把 TURN 服务器放在 1024 以下的端口（53/80/443 除外）。
- 浏览器经本机网卡地址访问本机 TURN；本机防火墙需允许本机访问通话端口。
- 服务端日志会打印每次 TURN 分配（`TURN: 已为 udp/... 分配中继`），通话连不上时可据此判断哪一端没连上中继。
- 通话端口不能放在 HTTP 反向代理后面，因为 TURN 流量走不了 HTTP 代理；控制台端口可以。

## Android 客户端

- 最低支持 Android 7.0（API 24），安装 Release 中的 `videocall-<ver>.apk`。
- 输入服务端地址、通话端口、接入密码后点"呼叫"。首次连接时需要核对证书指纹。
- 通话中可以静音、前后摄像头切换、开关摄像头、挂断。
- "记住密码"会用 Android Keystore 中的 AES-GCM 密钥加密保存。

`android/app/release.keystore` 和 `keystore.properties` 按需求直接提交在仓库里，本地构建和 CI 构建使用同一签名，所以可以直接覆盖升级。代价是：任何能读这个仓库的人都能用这个身份给 APK 签名。

## 构建与发版

```bash
cd server && go test ./... && go build -o videocall-server .
```

```bash
cd android && ./gradlew assembleRelease
```

发版方式：推送 `v*` 标签，或在 Actions 里手动运行 **Release** 工作流并填写版本号。工作流会构建 macOS arm64/amd64 和 Windows amd64 的服务端，以及签名 APK，附上 `SHA256SUMS.txt` 后发布到 GitHub Release。

## 已知限制

- 只支持一对一通话，Web 端和 Android 端同一时间各只能在线一个。
- Android 端在后台时系统会限制摄像头和麦克风，通话需要保持在前台（屏幕常亮已开启）。
- 服务端二进制没有做 Apple 签名和公证，也没有做 Windows Authenticode 签名。
