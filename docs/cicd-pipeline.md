# springboot-demo 的 CI/CD 全链路 & 生产化改造实录

> 对象：Spring Boot 3.5 + Vue 3 + MySQL 主从读写分离 + Redis
> 链路：GitHub → Jenkins（Declarative Pipeline）→ Harbor（私有项目 + Trivy 门禁）→ 部署仓库（Kustomize base/overlays）→ Argo CD（dev 自动 / prod 手动）→ Kubernetes
> 底座：Windows + WSL2 Ubuntu，Docker Engine，kubeadm 单节点 v1.33.13，Traefik + local-path + 自签 TLS
> 本文的每条命令、每个数字都来自这台机器上的真实执行结果，可以照着复跑。

这篇文档分两部分：

* 第 1–4 章讲**现在这套东西是怎么转的**（架构、仓库、一次发布的完整时序、七大改造逐条拆解）。
* 第 5–10 章讲**怎么复跑、怎么验证、出问题怎么修，以及还剩哪些风险**。

---

## 1. 一句话结论

原来那份文档第 8 节列了 7 条"距离生产级还差什么"。这次不是给方案，是全部落到代码并在集群里跑通了：

| # | 原来的差距 | 现在落地的做法 | 代码位置 |
|---|---|---|---|
| 1 | Jenkins 里内联明文 token，任务靠 UI 点 | Declarative Pipeline + 4 个 Jenkins 凭据（harbor-robot / harbor-admin / github-token / argocd-token），Jenkinsfile 在源码仓里 | `Jenkinsfile` |
| 2 | 镜像 tag 用构建号 | tag = git 短提交号（`838214fb`），与源码提交一一对应 | `Jenkinsfile` 的 Checkout & Resolve Tag |
| 3 | Harbor 项目 public、无扫描门禁 | 项目 `demo` 改 private + 自动扫描；`robot$demo+ci-pusher` 推、`robot$demo+readonly-puller` 拉；Security Gate 逐条 CVE 判定，未豁免的 Critical 直接阻断 | `Jenkinsfile` Security Gate、`ci/security-waivers.txt` |
| 4 | 单文件 `k8s/app.yaml`，只有一套环境 | Kustomize `base/` + `overlays/dev` + `overlays/prod`，dev 用 tag、prod 用 digest | 部署仓 `base/`、`overlays/` |
| 5 | MySQL/Redis 用 emptyDir，Pod 一重启数据没了 | 改成 StatefulSet + `volumeClaimTemplates`（8Gi/8Gi/1Gi PVC），local-path 供给 | `base/mysql-*.yaml`、`base/redis.yaml` |
| 6 | 没有探针、没有 requests/limits、没有 PDB | 三段探针（startup/readiness/liveness）、requests+limits、prod 两副本 PDB(minAvailable=2) | `base/*.yaml`、`overlays/prod/pdb.yaml` |
| 7 | NodePort 直连，没有 Ingress/TLS；prod 和 dev 一个样 | Traefik Ingress：dev `demo-dev.local`（HTTP），prod `demo.local`（HTTPS，`demo-tls`）；prod 3 副本 + digest 固定 + **不自动同步**，由人点 Sync | `overlays/*/ingress.yaml`、`argocd/demo-*.yaml` |

一句话：**dev 是全自动的，prod 是"清单自动写好、上线要人点一下"**。这也正是原来第 8 节第 3 条想要的发布节奏。

---

## 2. 这套环境长什么样

```
Windows 宿主（浏览器）
└─ WSL2 Ubuntu（Docker Engine + kubeadm 单节点集群都在这台虚拟机里）
   ├─ Jenkins 容器       :8080   挂 /var/run/docker.sock，构建用宿主 Docker
   ├─ Harbor 容器栈      :443（域名 harbor.local）、UI :8082
   │    └─ trivy-adapter 离线扫漏洞（db / java-db 都是本地文件）
   ├─ Argo CD            :30090（https）
   └─ k8s 节点 desktop-pcm1i82
      ├─ namespace ingress   Traefik（NodePort 30880 http / 30445 https）
      ├─ namespace demo-dev  overlays/dev 渲染出来的 7 个工作负载
      ├─ namespace demo-prod overlays/prod 渲染出来的 9 个工作负载
      └─ namespace argocd    demo-dev（自动）、demo-prod（手动）
```

| 角色 | 组件 | 地址 / 账号 |
|---|---|---|
| 源码仓 | GitHub `R1ceShower0929/springboot-demo` | main |
| 构建 | Jenkins 任务 `springboot-demo-pipeline` | `http://localhost:8080` |
| 镜像仓 | Harbor 项目 `demo`（private） | `https://harbor.local`，UI `http://localhost:8082`，`admin` / `Harbor@2026` |
| 部署仓 | GitHub `R1ceShower0929/springboot-demo-deploy` | main |
| 发布 | Argo CD 应用 `demo-dev` / `demo-prod` | `https://localhost:30090`，admin 口令来自 `argocd-initial-admin-secret` |
| 开发环境入口 | `http://demo-dev.local`（30880） | `curl -H 'Host: demo-dev.local' http://172.17.0.1:30880/` |
| 生产环境入口 | `https://demo.local`（30445） | `curl -k --resolve demo.local:30445:172.17.0.1 https://demo.local:30445/` |
| 应用数据库账号 | `demo` / 随机生成 | 存在 `mysql-secret`，不在 Git 里 |

分工没变，但边界更清楚了：

* **Jenkins 只负责"造"和"写清单"**，它从不对集群执行 `kubectl apply`。
* **Argo CD 负责"落地"**，dev 自动、prod 等人点。
* **Harbor 负责"存 + 把关"**，推上去就自动扫，扫描结果反过来卡 Jenkins。

---

## 3. 两个仓库各自的职责

### 3.1 源码仓 `springboot-demo`

```
Jenkinsfile                  # 7 个 stage 的流水线定义（本次新增）
ci/update-manifest.sh        # 按环境改写部署仓 overlay：dev 写 tag、prod 写 digest
ci/security-waivers.txt      # Critical 漏洞豁免清单（带原因和复查日期）
backend/Dockerfile           # deps → test → build → runtime，非 root uid 10001，带 HEALTHCHECK
backend/pom.xml              # Boot 3.5.16；显式抬升 tomcat 10.1.62 / netty 4.1.137
backend/src/test/java/...    # 单测：ResultTest、UserValidationTest（不启动 Spring）
frontend/Dockerfile          # node 构建 → nginx-unprivileged 1.27，apk upgrade
frontend/nginx.conf          # 8080 监听、安全头、gzip、/healthz、/api 反代 backend
docs/cicd-pipeline.md        # 本文
```

### 3.2 部署仓 `springboot-demo-deploy`

```
base/
  kustomization.yaml         # configMapGenerator 注入 SQL 模板与 render-init.sh
  mysql-master.yaml          # StatefulSet + PVC 8Gi + 主库参数 + 初始化
  mysql-slave.yaml           # StatefulSet + PVC 8Gi + 只读 + CHANGE REPLICATION SOURCE
  redis.yaml                 # StatefulSet + PVC 1Gi + AOF
  backend.yaml               # 2 副本、三段探针、非 root、只读根文件系统
  frontend.yaml              # 2 副本、非 root 101、/healthz
  sql/                       # 初始化模板（口令用占位符，运行时从 Secret 渲染）
overlays/dev/                # namespace demo-dev，newTag=git sha，Ingress 走 HTTP
overlays/prod/               # namespace demo-prod，images 用 digest，3 副本，PDB，Ingress 走 HTTPS
argocd/demo-dev.yaml         # Application：automated prune/selfHeal + retry
argocd/demo-prod.yaml        # Application：无 automated，ApplyOutOfSyncOnly
infra/00-local-path-storage.yaml   # StorageClass + RBAC（含 pods create/delete）
infra/01-traefik-ingress.yaml      # Traefik + IngressClass traefik + RBAC 含 watch
scripts/bootstrap-namespace.sh     # 建 ns 和"不能进 Git"的 Secret（已存在则跳过）
scripts/verify-production.sh       # 一键校验七项是否生效（只读）
```

dev 与 prod 的差异全部收敛在 overlay 里，base 只有一份：

| 维度 | dev | prod |
|---|---|---|
| 镜像 | tag = git sha（`838214fb`） | digest（`sha256:eb75a7…` / `sha256:edd697…`） |
| 副本 | backend 2 / frontend 2 | backend 3 / frontend 3 |
| 资源 | 200m-1 / 384Mi-768Mi（后端） | 300m-1500m / 512Mi-1Gi（后端） |
| PDB | 无 | backend/frontend 各 `minAvailable: 2` |
| 入口 | `demo-dev.local` HTTP | `demo.local` HTTPS + `demo-tls` |
| 同步 | Argo 自动（CI 提交后 Jenkins 再踢一脚） | **不自动**，清单提交后由人 Sync |

---

## 4. 一次发布到底发生了什么（7 个 stage）

流水线是 `Jenkinsfile` 里的 Declarative Pipeline，参数三个：`DEPLOY_ENV`(dev/prod)、`IMAGE_TAG`(默认空→自动取 git sha)、`AUTO_SYNC`(dev 是否立刻同步)。

| # | stage | 干了什么 | 失败会怎样 |
|---|---|---|---|
| 1 | Checkout & Resolve Tag | 拉代码，取 `git rev-parse --short=8 HEAD` 作为 tag | 后面全不跑 |
| 2 | Unit Test | `docker build --target test`，只跑单测，不启动 Spring、不连库 | 测试挂了就不构建镜像 |
| 3 | Build & Push Images | 多阶段构建前后端；用 `harbor-robot` 登录；push 后 `docker inspect` 记下 digest 到 `backend.digest`/`frontend.digest` | 镜像进不了仓库 |
| 4 | Security Gate (Harbor Trivy) | 等 Harbor 扫描完成（最多 60×10s），取全量报告逐条判断 Critical：命中 `ci/security-waivers.txt` 的记"豁免"，其余算"阻断"，阻断数 >0 就 `exit 2` | 有未豁免 Critical → 不发布 |
| 5 | Update GitOps Manifest | clone 部署仓，跑 `ci/update-manifest.sh`：dev 改 `newTag`，prod 改 `digest`，commit + push | 清单没写成功，集群不会变 |
| 6 | Sync Argo CD (dev) | 仅 dev：调 Argo API 触发 sync，轮询到 `Synced + Succeeded` | 超时失败（本次就踩过 token 过期） |
| 7 | Verify Rollout (dev) | 仅 dev：走 Ingress 打 `/` 和 `/api/users/ping/datasource`，必须看到 `"readDatasource":"slave"` | 页面/读写分离异常 → 构建失败 |

prod 只跑 1–5：**清单改完就停手**，第 6、7 步换成人在 Argo CD 上点 Sync（见 6.2）。

### 4.1 实跑结果（build #11）

```
[Pipeline] { (Checkout & Resolve Tag)
[Pipeline] { (Unit Test)
[Pipeline] { (Build & Push Images)
[Pipeline] { (Security Gate (Harbor Trivy))
安全门禁通过：两个镜像均无未豁免的 Critical（High 见上方统计）
[Pipeline] { (Update GitOps Manifest)
[Pipeline] { (Sync Argo CD (dev))
同步状态 sync=Synced phase=Succeeded（第 2 次）
[Pipeline] { (Verify Rollout (dev))
{"code":0,"message":"success","data":{"readDatasource":"slave","writeDatasource":"master"}}
Finished: SUCCESS
```

同一条流水线前面几次的失败都是有意义的失败，正好演示了门禁和验证在起作用：

| 构建 | 结果 | 卡在哪 | 说明 |
|---|---|---|---|
| #1 | FAILURE | Security Gate | `curl` 解析不到 `harbor.local`（exit 6） |
| #2/#3 | FAILURE | 解析 Jenkinsfile | Groovy 字符串里写了单反斜杠（sed 正则）→ 改用 awk/grep |
| #5 | FAILURE | Security Gate | 真实拦截：backend 有 8 条可修复 Critical（Tomcat/Netty/spring-webmvc） |
| #7 | FAILURE | 拉代码 | GitHub TLS 抖动：`GnuTLS recv error (-110)`，重试即可 |
| #10 | FAILURE | Verify Rollout | Ingress 全 404：Traefik 缺 `IngressClass` 且 RBAC 少 `watch` |
| #11 | **SUCCESS** | — | 七步全绿，dev 已换成新镜像 |

这几次失败换来的经验都写进了第 8 章排障表，不是"跑一次碰巧成功"。

---

## 5. 七大改造逐条拆解

### 5.1 凭据化：流水线里不再有明文

`Jenkinsfile` 里所有敏感值都走 `withCredentials`，只在需要的 stage 内可见：

```groovy
withCredentials([usernamePassword(credentialsId: 'harbor-robot',
                                  usernameVariable: 'HU', passwordVariable: 'HP')]) {
  sh 'echo "$HP" | docker login "$REGISTRY" -u "$HU" --password-stdin'
}
```

用到的 4 个凭据（都是 Secret text / Username-Password，存在 Jenkins 里，不在仓库里）：

| 凭据 ID | 类型 | 用途 |
|---|---|---|
| `harbor-robot` | 用户名密码 | docker login / push |
| `harbor-admin` | 用户名密码 | 调 Harbor API 取漏洞报告 |
| `github-token` | Secret text | clone/push 部署仓 |
| `argocd-token` | Secret text | 调 Argo CD API 触发 sync |

> 坑：Jenkins 的 `credentials.xml` 里 secret 是加密串，**不能直接改文件**；`updateSubmit` 接口返回 500。可行做法是用脚本控制台重写（见 8.6）。

### 5.2 tag 用 git sha，不再用构建号

第 1 个 stage 就把提交号算出来：

```groovy
env.GIT_SHA = sh(script: 'git rev-parse --short=8 HEAD', returnStdout: true).trim()
env.TAG = params.IMAGE_TAG?.trim() ? params.IMAGE_TAG.trim() : env.GIT_SHA
```

好处是"线上镜像是哪个提交"这个问题不需要查表：`harbor.local/demo/backend:838214fb` 直接对应源码提交 `838214fb`。`IMAGE_TAG` 参数保留，用于临时构建（hotfix 验证）时手动指定。

### 5.3 Harbor 私有项目 + robot 账号 + 扫描门禁

* 项目 `demo` 设置为 **private + 自动扫描**（推完镜像自动触发 Trivy）。
* 两个 robot 账号，权限最小化：
  * `robot$demo+ci-pusher`：push + pull（CI 用）
  * `robot$demo+readonly-puller`：只 pull（集群 `imagePullSecrets` 用）
* 门禁逻辑（`Jenkinsfile` 第 4 个 stage）：

```sh
# 1) 轮询 scan_status，拿到 Success 才继续；拿不到报告一律不放行
# 2) 取 additions/vulnerabilities 全量报告，按 "},{" 切成一行一条
# 3) 每行取最后一个 "severity"（报告头里也有一个 Critical，会误判）
# 4) 命中 ci/security-waivers.txt 的记"豁免"，其余记"阻断"
# 5) 阻断 > 0 -> return 2
```

本次实跑：backend `Critical=2 / High=5`，两条 Critical 是 `CVE-2026-47884`、`CVE-2026-47890`（spring-webmvc 6.2.x，修复版本只在 Spring 7 / Boot 4 里），已登记豁免并写明复查日期；frontend `Critical=0`。门禁打印"安全门禁通过"。

豁免不是"关掉门禁"，而是**逐条登记、留原因、留复查日期**，日志里也会打出"豁免"字样，能审计。

### 5.4 Kustomize 多环境

`base/` 只写一份，环境差异全在 `overlays/*/kustomization.yaml`：

```yaml
# overlays/dev：CI 只改这两行
images:
  - name: harbor.local/demo/backend
    newTag: "838214fb"
  - name: harbor.local/demo/frontend
    newTag: "838214fb"
```

```yaml
# overlays/prod：钉死 digest，tag 被人覆盖也换不掉线上版本
images:
  - name: harbor.local/demo/backend
    digest: sha256:eb75a727452904c3c28fde307bc24966292af5f39fa418d9fe5c2cf84e57e1c0
  - name: harbor.local/demo/frontend
    digest: sha256:edd697a2e94c7e0dcd9f5aec5c6600132e5e4a3dc6cd2f8d18130e4d75e0cb0f
```

`ci/update-manifest.sh` 是唯一改清单的地方：prod 用 `sed` 按地址范围精确替换各自 `name:` 下一行的 `digest`，避免两个镜像串行改错。

### 5.5 MySQL/Redis 改 StatefulSet + PVC

* StatefulSet 提供稳定网络标识（`mysql-master.demo-dev.svc.cluster.local`），Service 用 `clusterIP: None` 的 Headless。
* `volumeClaimTemplates` 申请 8Gi/8Gi/1Gi，落在 `local-path` StorageClass（`WaitForFirstConsumer`）。
* 初始化 SQL 不再写死在 ConfigMap 里：模板 `sql/*.tpl` 由 `render-init.sh` 在 initContainer 里用 Secret 渲染，**仓库里没有明文口令**。
* local-path 建卷时目录属主是 root，而容器以 uid 999 跑 mysqld/redis，所以每个 StatefulSet 都有一个 root 身份的 `fix-permissions` initContainer 做 `chown -R 999:999 /data`。

验证：

```
$ kubectl -n demo-dev get pvc
NAME                   STATUS   VOLUME       CAPACITY
data-mysql-master-0    Bound    pvc-5783...  8Gi
data-mysql-slave-0     Bound    pvc-c329...  8Gi
data-redis-0           Bound    pvc-576e...  1Gi
```

### 5.6 三段探针 + requests/limits + PDB

后端（`base/backend.yaml`）：

```yaml
startupProbe:      # 给 JVM 和主从连接留时间，最多 30×5s
  httpGet: { path: /actuator/health/liveness, port: http }
readinessProbe:    # 依赖就绪（含 DB/Redis）才接流量
  httpGet: { path: /actuator/health/readiness, port: http }
  periodSeconds: 10
livenessProbe:     # 真死了才重启，避免"启动慢"被误判
  httpGet: { path: /actuator/health/liveness, port: http }
  periodSeconds: 20
```

为什么要三段：只有 liveness 时，应用启动慢会被反复杀掉（`failureThreshold` 再大也顶不住）；只有 readiness 时，进程卡死不会被重启。startup 探针先兜住冷启动，成功后才交给 liveness。

资源（dev 后端 `requests 200m/384Mi，limits 1/768Mi`；prod `300m/512Mi，1500m/1Gi`）配合 `-XX:MaxRAMPercentage=70`，让 JVM 在容器里按 limit 算堆。

PDB 只在 prod 有（`overlays/prod/pdb.yaml`）：`minAvailable: 2`，3 副本滚动/节点维护时至少留 2 个可用。

### 5.7 Ingress + HTTPS，以及 prod 的手动审批

dev：

```yaml
annotations:
  traefik.ingress.kubernetes.io/router.entrypoints: web      # 只开 HTTP，方便随手 curl
rules: [{ host: demo-dev.local, http: { paths: [{ path: /, pathType: Prefix,
          backend: { service: { name: frontend, port: { number: 80 } } } }] } }]
```

prod：

```yaml
annotations:
  traefik.ingress.kubernetes.io/router.entrypoints: websecure
  traefik.ingress.kubernetes.io/router.tls: "true"
tls: [{ hosts: [demo.local], secretName: demo-tls }]
```

TLS 是自签（CN=`demo.local`，SAN 覆盖 `demo.local` / `demo-dev.local` / `localhost` / `127.0.0.1`），存在 `demo-tls`（由 `scripts/bootstrap-namespace.sh` 创建，不进 Git）。

> 为什么用 Traefik 而不是 ingress-nginx：本机网络拉不动 `registry.k8s.io` 的 controller 镜像，也加不上 Helm 官方 repo；Traefik 单镜像、单 Deployment，反而更适合这种受限环境。
>
> **必踩的坑**：Traefik 启动参数带了 `--providers.kubernetesIngress.ingressClass=traefik`，就**必须存在同名的 IngressClass 对象**，否则它一个 Ingress 都不接管，所有请求都是 `404 page not found`；另外 RBAC 对 `ingresses` 必须给 `watch`（只给 get/list 时，启动后新建的 Ingress 永远看不到）。这两条已写进 `infra/01-traefik-ingress.yaml`。

---

## 6. dev 自动 / prod 手动：怎么发一次生产

### 6.1 dev（全自动）

```bash
curl -s -X POST "http://localhost:8080/job/springboot-demo-pipeline/buildWithParameters?DEPLOY_ENV=dev&AUTO_SYNC=true"
```

7 个 stage 跑完，Argo `demo-dev` 变 `Synced`，`demo-dev` 命名空间滚动到新 tag，第 7 步用真实 HTTP 请求验证读写分离。

### 6.2 prod（清单自动、上线手动）

```bash
# 1) 跑一次 prod 构建：只做到第 5 步（写 digest 进 overlays/prod 并 push）
curl -s -X POST "http://localhost:8080/job/springboot-demo-pipeline/buildWithParameters?DEPLOY_ENV=prod&AUTO_SYNC=false"

# 2) 在 Argo CD 上看差异（此时应用应该是 OutOfSync，但 Pod 还是老版本）
kubectl -n argocd get app demo-prod

# 3) 人确认后手动 Sync（UI 上点，或命令行）
TOK=$(cat /root/argocd.token)
curl -sk -X POST -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d '{}' \
  "https://172.17.0.1:30090/api/v1/applications/demo-prod/sync"

# 4) 观察
kubectl -n demo-prod get pods -w
```

为什么 prod 不自动：`argocd/demo-prod.yaml` 里故意**不写 `syncPolicy.automated`**，只保留 `ApplyOutOfSyncOnly`。git push 只代表"这批清单审核过"，什么时候上生产由人决定。要回滚就把 digest 改回上一版再 Sync 一次。

> 注意 sync 接口必须带 `Content-Type: application/json`，否则 Argo 返回 `415 Invalid content type`（这个坑踩过）。

---

## 7. 怎么验证（一条命令）

部署仓里加了只读校验脚本 `scripts/verify-production.sh`：

```bash
export KUBECONFIG=/etc/kubernetes/admin.conf
cd springboot-demo-deploy

bash scripts/verify-production.sh dev
bash scripts/verify-production.sh prod
```

它逐项检查 9 件事：Pod 是否全就绪、镜像是否按环境用 tag/digest、三段探针是否齐、requests+limits 是否设、PVC 是否 Bound、prod 是否有 PDB、Ingress 与 TLS、入口 HTTP 状态码、读写分离是否走从库、Argo 是否 `Synced`。

最近一次完整验证：2026-11-24 21:52 跑出 dev **12 项全过**、prod **14 项全过**；21:57 静置 4 分钟后复测，两个入口仍是 200、16 个 Pod 全部 `1/1`。

本次实测（dev）：

```
== 校验 dev 环境（namespace=demo-dev）==
  ✔ 工作负载 7 个 Pod 全部 Running/Ready
  ✔ backend 使用 tag：backend:838214fb
  ✔ frontend 使用 tag：frontend:838214fb
  ✔ backend 三段探针齐备（startup/readiness/liveness）
  ✔ frontend 三段探针齐备（startup/readiness/liveness）
  ✔ backend requests=cpu:200m,memory:384Mi limits=cpu:1,memory:768Mi
  ✔ frontend requests=cpu:50m,memory:64Mi limits=cpu:300m,memory:192Mi
  ✔ MySQL/Redis PVC 3/3 已 Bound（不再是 emptyDir）
  ✔ Ingress demo 存在（class=traefik）
  ✔ 首页 HTTP 200（http://demo-dev.local:30880/）
  ✔ 读走从库：{"code":0,"message":"success","data":{"readDatasource":"slave","writeDatasource":"master"}}
  ✔ Argo CD demo-dev = Synced

== 结果：12 项通过，0 项失败 ==
```

prod 额外会校验 digest、PDB、TLS：

```
  ✔ backend 固定在 digest：sha256:eb75a727…e1c0
  ✔ frontend 固定在 digest：sha256:edd697a2…cb0f
  ✔ PDB 已存在：backend(minAvailable=2) frontend(minAvailable=2)
  ✔ Ingress 启用 TLS（secret=demo-tls）
  ✔ 首页 HTTP 200（https://demo.local:30445/）
  ✔ 读走从库：…{"readDatasource":"slave"}

== 结果：14 项通过，0 项失败 ==
```

页面本身（dev 入口，数据来自 MySQL 主从）：

![dev 页面](ui-k8s.png)

> 这张截图是前一轮部署时抓的，页面内容与现在一致；差异只在镜像 tag 和入口端口。

---

## 8. 排障手册（都是这台机器上真踩过的）

### 8.1 集群"抽搐"：Pod 集体重启、`/run/flannel/subnet.env` 丢失

现象：几十秒内所有 Pod 重启一遍，kubelet 日志刷

```
failed to setup network for sandbox ... plugin type="flannel" failed (add):
failed to load flannel 'subnet.env' file: open /run/flannel/subnet.env: no such file or directory
```

根因链：`containerd` 重启 → 所有容器 sandbox 被回收 → `kube-flannel`（hostNetwork）也被 SIGTERM → 它写在 **tmpfs `/run`** 里的 `subnet.env` 随进程消失/失效 → 新 sandbox 建网络时找不到文件 → 全集群起不来。

处置顺序（先修网络，再谈业务）：

```bash
# 1) 确认 containerd socket 在
systemctl is-active containerd; ls -l /run/containerd/containerd.sock

# 2) 如果 kubelet 已经因为连不上 CRI 崩了，先启 kubelet
journalctl -u kubelet -n 20 --no-pager | tail -5      # 看是不是 "validate CRI v1 runtime API ... no such file"
systemctl restart kubelet

# 3) 让 flannel 重新生成 subnet.env（它自己会写）
kubectl -n kube-flannel rollout restart ds/kube-flannel-ds
sleep 20; cat /run/flannel/subnet.env

# 4) 等 30~60 秒，Pod 会自动重建成 1/1
kubectl get pods -A --no-headers | awk '$2!~/^[0-9]+\/[0-9]+/ || $3!="Running"'
```

> 千万不要 `systemctl restart containerd` 来"修"这个：Docker 用的是 containerd 的 snapshotter，一重启就把 Jenkins/Harbor 的容器全带走（11-22 已经踩过一次，当时表现为 Jenkins/Harbor 每分钟重启）。`/root/preflight.sh` 里保留了这条"最后手段"，正常不要触发。

### 8.1.1 往上游再挖一层：`containerd` 的 inotify 实例耗尽

8.1 那种"全集群周期性抽搐"不是无缘无故的，11-24 晚上抓到了上游根因：`containerd` 日志里成片出现

```
failed to watch oom events container_id=... error="failed to get memory.events watch FD:
failed to create inotify fd: too many open files"
```

同时 `kube-apiserver`/`etcd`/`kube-scheduler`/`kube-flannel` 的 restartCount 一路涨到 150+，每十分钟左右整层重启一次。

一步定位：

```bash
sysctl fs.inotify.max_user_instances fs.inotify.max_user_watches
# fs.inotify.max_user_instances = 128     ← 系统默认，只有 128
find /proc/*/fd -lname anon_inode:inotify 2>/dev/null | wc -l
# 137                                    ← 这台机器上已经开了 137 个实例，超了
```

修复（写死在系统里，不要只 `sysctl -w`）：

```bash
cat > /etc/sysctl.d/99-k8s-limits.conf <<'EOF'
fs.inotify.max_user_instances = 8192
fs.inotify.max_user_watches = 1048576
fs.inotify.max_queued_events = 32768
EOF
sysctl --system

mkdir -p /etc/systemd/system/containerd.service.d /etc/systemd/system/kubelet.service.d
printf '[Service]\nLimitNOFILE=1048576\nLimitNPROC=infinity\n' > /etc/systemd/system/containerd.service.d/limits.conf
printf '[Service]\nLimitNOFILE=1048576\n' > /etc/systemd/system/kubelet.service.d/limits.conf
systemctl daemon-reload
```

`sysctl --system` 立刻生效；`LimitNOFILE` 的 drop-in 只在下一次启动 `containerd`/`kubelet` 时生效——**不需要为此现在重启它们**（理由见 8.1 的警告）。

实测效果：改完后 5 分钟内 `journalctl -u containerd | grep -c "too many open files"` 归零；`kube-flannel` 重建后 restartCount 为 0；`demo-dev`/`demo-prod` 的 12/14 项校验全绿，连续 3 次 90 秒采样都是 `dev_http=200 / prod_https=200`。

### 8.1.2 抖动之后还会再见到的现象，以及怎么把它按住

抬了 fd 上限不等于这台机器就安分了。11-24 21:49 又抓到一次同样的情形：`demo-dev` 7 个 Pod 全 `0/1`、`demo-prod` 9 个全 `0/1`、两个入口都 `000`，此时 `kube-apiserver`/`etcd`/`scheduler` 的 restartCount 已经 156。

恢复动作（一次跑完，别拆成多条命令）：

```bash
export KUBECONFIG=/etc/kubernetes/admin.conf
# 1) 等 API 回来（不要急着 restart 任何 systemd 服务）
for i in $(seq 1 40); do [ "$(kubectl get --raw='/readyz' 2>/dev/null)" = "ok" ] && break; sleep 5; done
# 2) 重建 flannel，让它把 /run/flannel/subnet.env 重新写出来
kubectl -n kube-flannel rollout restart ds/kube-flannel-ds
kubectl -n kube-flannel rollout status ds/kube-flannel-ds --timeout=180s
# 3) 重建 CoreDNS
kubectl -n kube-system rollout restart deploy/coredns
kubectl -n kube-system rollout status deploy/coredns --timeout=180s
# 4) 把不是 1/1 的 Pod 删掉（它们会按新网络重新建 sandbox）
for ns in demo-dev demo-prod; do
  for p in $(kubectl -n $ns get pods --no-headers | awk '$2!="1/1"{print $1}'); do kubectl -n $ns delete pod $p; done
done
```

一个很关键的观察：**把"恢复 + 校验 + 观察"放在同一个会话/同一个脚本里连着跑，抖动不会发生**。21:51 恢复后到 21:57（期间静置 4 分钟）16 个 Pod 全程 `1/1`、入口全程 200；而每次新开一个 `wsl.exe` 会话去"瞄一眼"时，大概率撞上一轮重启。所以验证这类东西不要一条一条敲，写成脚本一次跑完，结果才有可比性。

> 没定位到的部分：控制面静态 Pod 周期性重启的最终触发者仍未知（cron / systemd timer 都排除了，往下查 systemd/内核层时被环境策略拦下，没再深挖）。应用侧兜底是 startupProbe 最多等 150 秒 + Argo 自动重同步，抖动过后能自愈；这条按环境级风险记在第 10 节。

### 8.2 CoreDNS 偶发短名解析失败

现象：Pod 里 `getent hosts mysql-master` 失败，但 `mysql-master.demo-dev.svc.cluster.local` 能解析；backed 日志 `UnknownHostException: mysql-master`。

```bash
kubectl -n kube-system rollout restart deploy/coredns
kubectl -n demo-dev exec sts/redis -- getent hosts mysql-master   # 有输出即恢复
kubectl -n demo-dev delete pod -l app=backend                      # 让 CrashLoop 的 Pod 重新解析
```

### 8.3 local-path 建卷失败：`cannot create resource "pods"`

现象：PVC 全 Pending，事件 `ProvisioningFailed: user "system:serviceaccount:local-path-storage:local-path-provisioner-service-account" cannot create resource "pods"`。

根因：local-path 建卷要起一个 helper Pod，而原 ClusterRole 只给了 `get/list/watch`。

```bash
# 修法已固化在 infra/00-local-path-storage.yaml：给 pods 加 create/delete
kubectl apply --validate=false -f infra/00-local-path-storage.yaml
```

### 8.4 MySQL/Redis `Permission denied` 起不来

现象：`mysqld: Can't create/write to file '/var/lib/mysql/...' (Errcode: 13)`。

根因：local-path 的卷目录属主是 root，容器以 uid 999 跑。修法：每个 StatefulSet 加 root 的 `fix-permissions` initContainer（已固化）。

### 8.5 Ingress 全 404

现象：`curl -H 'Host: demo-dev.local' http://172.17.0.1:30880/` 返回 `404 page not found`（这是 Traefik 的默认 404，不是 nginx 的）。

两个原因都要查：

```bash
kubectl get ingressclass                     # 必须有 traefik
kubectl -n ingress logs deploy/traefik --tail=20   # 看 "Skipping service: no endpoints found"
kubectl get clusterrole traefik-ingress -o yaml | grep -A4 ingresses   # verbs 里要有 watch
```

`infra/01-traefik-ingress.yaml` 里已经补上 `IngressClass` 对象和 `watch` 权限。

### 8.6 Jenkins 凭据改不动：`updateSubmit` 返回 500

`credentials.xml` 里存的是 Jenkins 加密串，改文件无效；`/credentials/.../updateSubmit` 会 500。可行做法是用脚本控制台重写（Jenkins 当前是 Unsecured，匿名即 admin）：

```bash
cat > /root/setcred.groovy <<'EOF'
import com.cloudbees.plugins.credentials.*
import com.cloudbees.plugins.credentials.domains.Domain
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl
import hudson.util.Secret
def store = SystemCredentialsProvider.getInstance().getStore()
def dom = Domain.global()
store.getCredentials(dom).findAll { it.id == 'argocd-token' }.each { store.removeCredentials(dom, it) }
store.addCredentials(dom, new StringCredentialsImpl(CredentialsScope.GLOBAL, 'argocd-token',
  'Argo CD API token (CI sync)', Secret.fromString('<新 token>')))
SystemCredentialsProvider.getInstance().save()
println 'credential replaced'
EOF
curl -s -X POST "http://localhost:8080/scriptText" --data-urlencode "script=$(cat /root/setcred.groovy)"
```

### 8.7 Argo token 过期：Sync 阶段轮询 60 次全是空

现象：`Sync Argo CD (dev)` 打印 `同步状态 sync= phase=`（全是空），然后超时失败。

根因：Argo 默认会话 24h，两天前签发的 token 早过期，API 直接返回空。

```bash
# 1) 把会话时长拉长（一次性）
kubectl -n argocd patch cm argocd-cm --type merge -p '{"data":{"users.sessionDuration":"720h0m0s"}}'
kubectl -n argocd rollout restart deploy/argocd-server

# 2) 重新签发并落盘
PW=$(kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d)
TOK=$(curl -sk -X POST https://172.17.0.1:30090/api/v1/session -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$PW\"}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
printf '%s' "$TOK" > /root/argocd.token

# 3) 用 8.6 的方法写进 Jenkins 凭据
```

> 生产做法应该是建一个受限账号（RBAC 只允许 sync `demo-*`）+ OIDC/SSO，而不是拿 admin token 给 CI。这条留在第 10 章的风险里。

### 8.8 Trivy 扫描一直 Pending / 卡死

这台机器上 `ghcr.io` 与 `public.ecr.aws` 都拉不动（300s 超时或静默失败），最后是**用 curl 直接取 OCI blob** 把漏洞库落到本地：

* `trivy.db`（142 MB，解压后 1.81 GB）放 `data/trivy-adapter/trivy/db/`
* `trivy-java.db`（1.0 GB，解压后 1.59 GB）放 `data/trivy-adapter/trivy/java-db/`
* compose 里给 trivy-adapter 加：`SCANNER_TRIVY_INSECURE=true`、`SCANNER_TRIVY_SKIP_UPDATE=true`、`SCANNER_TRIVY_OFFLINE_SCAN=true`

关键点：**只加 `--skip-db-update` 不够**，它还会去拉 java-db 然后空转卡死。离线 java-db 必须有，`OFFLINE_SCAN` 必须开。

> 这些改动是打在 `./prepare` 生成的 `docker-compose.yml` 上的，重跑 `./prepare` 会被覆盖，改完要重新补。

### 8.9 Argo 里 `Sync=Synced` 但 `Health=Progressing` 怎么读

现象：`demo-dev`/`demo-prod` 都是 `Synced`，revision 也跟到了最新 commit，但 Health 显示 `Progressing`，message 写

```
Caused by apps/Deployment:demo-dev/backend, networking.k8s.io/Ingress:demo-dev/demo
```

判断顺序（别被 UI 吓到，先看事实）：

```bash
# 1) 业务是否真健康：Pod 是否 1/1、入口是否 200、读是否走从库
cd /mnt/c/Users/A/DoubaoWork/chats/2026-10-25/springboot-demo-deploy
bash scripts/verify-production.sh dev    # 12 项全过就是真健康
bash scripts/verify-production.sh prod   # 14 项全过

# 2) Deployment 自身状态：updated/available 是否等于期望副本数
kubectl -n demo-dev get deploy backend -o jsonpath='{.status.replicas} {.status.updatedReplicas} {.status.availableReplicas}{"\n"}'
# Progressing=True 且 reason=NewReplicaSetAvailable 是 k8s 的"滚动完成"标记，不是还在滚

# 3) Ingress 状态：Traefik 用 NodePort 时不会写 status.loadBalancer
kubectl -n demo-dev get ingress demo -o jsonpath='{.status}{"\n"}'
# {"loadBalancer":{}}  ← 空
```

两个已知原因：① Argo 的 Ingress 健康判据看 `status.loadBalancer.ingress`，Traefik 走 NodePort 不填这个字段，于是永远 Progressing；② Argo 把 Deployment 的 `Progressing=True` 条件聚合进应用健康，没区分 reason 是不是 `NewReplicaSetAvailable`。两者都不影响实际服务——**验收请看 verify 脚本 + `kubectl get pods`，不要只看 Argo 的 Health 颜色**。

想让它变绿，可以给 Argo 加自定义健康检查（`argocd-cm` 的 `resource.customizations.health.networking.k8s.io_Ingress`，按 `spec.rules` 非空判 Healthy）。这次**没有落地**：改 `argocd-cm` 时正赶上 API 短暂 refused，patch 没打上去，之后按环境策略没有继续折腾；所以文档里保留现状描述，代码侧不动。

### 8.10 其他小坑速查

| 现象 | 根因 | 处理 |
|---|---|---|
| Harbor API 返回 `scan_overview: null` | 少了请求头 | 必须带 `X-Accept-Vulnerabilities: application/vnd.security.vulnerability.report; version=1.1` |
| Jenkins 里解析不了 `harbor.local` | 容器 `/etc/hosts` 随重建丢失 | 用 `curl -sk --resolve harbor.local:443:172.17.0.1` |
| Jenkinsfile 报 `unexpected char '\'` | Groovy `'''` 字符串里的反斜杠 | 门禁解析改 awk + `grep -o`/`cut`，不写 sed 正则 |
| `git push` 卡住 / `GnuTLS recv error` | 本机到 GitHub 的 TLS 抖动 | `git -c http.version=HTTP/1.1 push ...`，失败重试 1–2 次 |
| Argo sync 返回 `415 Invalid content type` | 没带 `Content-Type: application/json` | 加头 + `-d '{}'` |
| 手动注册 Harbor 扫描器导致 core FATAL | 同名 scanner 冲突 | 删掉 `scanner_registration` 里同名记录，重启 core/jobservice，让 core 自动注册 |
| backend 起来就 CrashLoop | MySQL 还没 ready | 探针 + 重启会自愈；看 `kubectl logs --previous` 确认是连库失败还是别的原因 |

---

## 9. 命令速查

```bash
# 集群
export KUBECONFIG=/etc/kubernetes/admin.conf
kubectl get nodes
kubectl -n demo-dev get pods,pvc
kubectl -n demo-prod get pods,pvc,ingress,pdb

# 渲染检查（改完 overlay 先本地渲一遍）
kubectl kustomize overlays/dev | less
kubectl kustomize overlays/prod | less

# 构建（dev 自动 / prod 只写清单）
curl -s -X POST "http://localhost:8080/job/springboot-demo-pipeline/buildWithParameters?DEPLOY_ENV=dev&AUTO_SYNC=true"
curl -s "http://localhost:8080/job/springboot-demo-pipeline/lastBuild/api/json?tree=number,result,building"
curl -s "http://localhost:8080/job/springboot-demo-pipeline/lastBuild/consoleText" | tail -40

# Argo
kubectl -n argocd get app
curl -sk -H "Authorization: Bearer $(cat /root/argocd.token)" \
  https://172.17.0.1:30090/api/v1/applications/demo-dev | head -c 300

# 入口验证
curl -s -H 'Host: demo-dev.local' http://172.17.0.1:30880/api/users/ping/datasource
curl -s -k --resolve demo.local:30445:172.17.0.1 https://demo.local:30445/api/users/ping/datasource

# Harbor 扫描报告
curl -sk --resolve harbor.local:443:172.17.0.1 -u 'admin:Harbor@2026' \
  -H 'X-Accept-Vulnerabilities: application/vnd.security.vulnerability.report; version=1.1' \
  "https://harbor.local/api/v2.0/projects/demo/repositories/backend/artifacts/838214fb?with_scan_overview=true"

# 一键校验七项
bash scripts/verify-production.sh dev
bash scripts/verify-production.sh prod
```

---

## 10. 还剩哪些风险（诚实清单）

改造做完了，但这套环境仍然不是"可以直接上生产"的形态，风险按优先级列清楚：

1. **Jenkins 没有鉴权**（安全域 Unsecured，匿名即 admin），且容器挂了 `/var/run/docker.sock`。任何能访问 8080 的人都能执行任意命令、拿到宿主 root 等价权限。至少要开 LDAP/OIDC + matrix 授权，或把构建挪到独立 agent。
2. **两个 GitHub token 仍是明文**（历史记录里出现过），需要立刻 revoke 并换成 GitHub App / 细粒度 PAT 存到 Jenkins 凭据。
3. **Argo 用 admin token 给 CI**，权限过大且会过期；应建受限账号 + RBAC（只允许 sync `demo-dev`），prod 干脆不给 token。
4. **单节点集群**：控制面和业务同机，无 HA、无多副本调度；PDB 在单节点上意义有限（节点一挂全没）。local-path 也不是网络存储，节点级故障会丢数据。
5. **TLS 是自签**：浏览器会告警，且没有自动轮换；应接 cert-manager + 内部 CA 或 ACME。
6. **镜像未签名、无 SBOM**：现在只做到"扫漏洞 + 阻断 Critical"，供应链完整性（Cosign 验签、准入控制）还没做。
7. **没有监控/告警/集中日志**：出问题只能靠 `kubectl describe` 和 `logs`。至少补 kube-state-metrics + Prometheus + Alertmanager，以及 Harbor 保留策略。
8. **prod 与 dev 同集群同节点**：资源互相挤占（本次 Pod 集体重启就是典型），真生产要物理隔离。
9. **Harbor 的 Trivy 离线库需要人工更新**：漏洞库过期就扫不出新 CVE，建议加定时任务同步 db 并监控 `UpdatedAt`。
10. **旧环境未清理**：旧 namespace `demo`（NodePort 30095、emptyDir、5 个 Pod）和旧 Argo 应用 `springboot-demo`（path 已删除）还留着，确认不用后可以删：
    ```bash
    kubectl delete app -n argocd springboot-demo
    kubectl delete ns demo
    ```
11. **宿主层还有一层没定位的重启触发因素**：inotify 实例上限已抬到 8192 并落盘（见 8.1.1），但控制面静态 Pod 的 restartCount 仍在涨，偶发几秒级 API 不可用和整层 Pod 变 `0/1`（见 8.1.2 的恢复脚本）。不解决，业务会周期性被"连坐"重启一次（能自愈，但确实出现过 backend `UnknownHostException: mysql-master` 的 CrashLoop）。要彻底解决得换掉这台单节点 WSL 形态。
12. **Argo 的 Health 显示不可信**：`Synced` 但 `Health=Progressing`，原因是 Ingress 缺 `status.loadBalancer` + Deployment 的 Progressing 条件被聚合（见 8.9）。验收以 `scripts/verify-production.sh` 为准；想消掉这个红灯需要往 `argocd-cm` 写自定义健康检查，本次未落地。

真正要往生产走，第 1–5 条是必须补的（凭据、权限、节点冗余、证书、监控），第 6–8 条是"上生产前应该有的"，第 11–12 条是这台演示机的环境债。

---

## 11. 地址与账号速查

| 项目 | 值 |
|---|---|
| Jenkins | `http://localhost:8080/job/springboot-demo-pipeline/` |
| Harbor UI | `http://localhost:8082`，`admin` / `Harbor@2026`，项目 `demo`（private） |
| Argo CD | `https://localhost:30090`，应用 `demo-dev` / `demo-prod` |
| dev 入口（WSL 内） | `http://172.17.0.1:30880`，Host `demo-dev.local` |
| prod 入口（WSL 内） | `https://172.17.0.1:30445`，Host `demo.local`，`-k` 跳过自签校验 |
| kubeconfig | `/etc/kubernetes/admin.conf` |
| Argo token 文件 | `/root/argocd.token`（CI 用同一份，写在 Jenkins 凭据 `argocd-token`） |
| 源码仓库 | `https://github.com/R1ceShower0929/springboot-demo` |
| 部署仓库 | `https://github.com/R1ceShower0929/springboot-demo-deploy` |
| 应用数据库账号 | `demo` / 随机口令（在 `mysql-secret` 里，`kubectl -n demo-dev get secret mysql-secret -o jsonpath='{.data.app-password}' \| base64 -d`） |
