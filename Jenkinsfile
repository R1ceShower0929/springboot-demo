pipeline {
  agent any

  options {
    // 本机 Jenkins 未装 timestamper 插件，故不加 timestamps()（日志时间戳由 console 的 “View as plain text” 提供）
    disableConcurrentBuilds()
    buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '5'))
    timeout(time: 45, unit: 'MINUTES')
  }

  parameters {
    choice(name: 'DEPLOY_ENV', choices: ['dev', 'prod'],
           description: 'dev：构建成功后自动触发 Argo 同步；prod：只提交清单，由人在 Argo CD 上手动 Sync')
    string(name: 'IMAGE_TAG', defaultValue: '',
           description: '留空则用 git 短提交号（推荐，可回溯到具体代码）')
    booleanParam(name: 'AUTO_SYNC', defaultValue: true,
                 description: 'dev 环境是否在构建成功后立刻触发 Argo 同步')
  }

  environment {
    REGISTRY     = 'harbor.local'
    HARBOR_URL   = 'https://harbor.local'
    PROJECT      = 'demo'
    DEPLOY_REPO  = 'github.com/R1ceShower0929/springboot-demo-deploy.git'
    // Jenkins 跑在容器里，Argo Server 在宿主 30090（容器内经 docker 网关访问）
    ARGO_URL     = 'https://172.17.0.1:30090'
    ARGO_APP     = "${params.DEPLOY_ENV == 'prod' ? 'demo-prod' : 'demo-dev'}"
    OVERLAY      = "${params.DEPLOY_ENV == 'prod' ? 'overlays/prod' : 'overlays/dev'}"
  }

  stages {

    stage('Checkout & Resolve Tag') {
      steps {
        checkout scm
        script {
          env.GIT_SHA = sh(script: 'git rev-parse --short=8 HEAD', returnStdout: true).trim()
          env.TAG = params.IMAGE_TAG?.trim() ? params.IMAGE_TAG.trim() : env.GIT_SHA
          env.GIT_SUBJECT = sh(script: 'git log -1 --pretty=%s', returnStdout: true).trim()
          echo "构建标签: ${env.TAG}（提交 ${env.GIT_SHA}：${env.GIT_SUBJECT}）"
        }
      }
    }

    stage('Unit Test') {
      steps {
        echo '--- 只跑单元测试（不启动 Spring、不连库）---'
        sh 'docker build --target test -t springboot-demo-backend:test ./backend'
      }
    }

    stage('Build & Push Images') {
      steps {
        withCredentials([usernamePassword(credentialsId: 'harbor-robot',
                                          usernameVariable: 'HU', passwordVariable: 'HP')]) {
          sh '''
            set -eu
            echo "--- 登录 Harbor（robot 账号，只在本 stage 内可见）---"
            echo "$HP" | docker login "$REGISTRY" -u "$HU" --password-stdin

            echo "--- 构建后端（多阶段：依赖 -> 测试 -> 打包 -> 非 root 运行）---"
            docker build -t "$REGISTRY/$PROJECT/backend:$TAG" ./backend

            echo "--- 构建前端（Node 打包 -> unprivileged Nginx）---"
            docker build -t "$REGISTRY/$PROJECT/frontend:$TAG" ./frontend

            echo "--- 推送（tag = git 提交号，可回溯；Harbor 侧自动触发 Trivy 扫描）---"
            docker push "$REGISTRY/$PROJECT/backend:$TAG"
            docker push "$REGISTRY/$PROJECT/frontend:$TAG"

            echo "--- 记录不可变 digest（prod 环境用 digest 部署）---"
            docker inspect --format '{{index .RepoDigests 0}}' "$REGISTRY/$PROJECT/backend:$TAG"  > backend.digest
            docker inspect --format '{{index .RepoDigests 0}}' "$REGISTRY/$PROJECT/frontend:$TAG" > frontend.digest
            cat backend.digest frontend.digest
          '''
        }
      }
      post {
        always {
          sh 'docker logout "$REGISTRY" || true'
        }
      }
    }

    stage('Security Gate (Harbor Trivy)') {
      steps {
        withCredentials([usernamePassword(credentialsId: 'harbor-admin',
                                          usernameVariable: 'AU', passwordVariable: 'AP')]) {
          sh '''
            set -eu
            gate() {
              repo="$1"; tag="$2"
              url="$HARBOR_URL/api/v2.0/projects/$PROJECT/repositories/$repo/artifacts/$tag?with_scan_overview=true"
              for i in $(seq 1 40); do
                body=$(curl -sk -u "$AU:$AP" "$url")
                status=$(echo "$body" | grep -o '"scan_status":"[^"]*"' | head -1 | cut -d'"' -f4)
                [ "$status" = "Success" ] && break
                echo "扫描未完成（$repo:$tag，第 $i 次，状态=${status:-Pending}），继续等待…"
                sleep 10
              done
              if [ "$status" != "Success" ]; then
                echo "扫描超时或失败：$repo:$tag（status=${status:-unknown}）"; return 1
              fi
              crit=$(echo "$body" | grep -o '"Critical":[0-9]*' | head -1 | cut -d: -f2); crit=${crit:-0}
              high=$(echo "$body" | grep -o '"High":[0-9]*'     | head -1 | cut -d: -f2); high=${high:-0}
              echo "漏洞统计 $repo:$tag -> Critical=$crit High=$high"
              echo "$crit" > /tmp/crit.$repo
              [ "$crit" -eq 0 ] || { echo "存在 Critical 漏洞，阻断发布"; return 2; }
            }
            gate backend  "$TAG"
            gate frontend "$TAG"
            echo "安全门禁通过（无 Critical；High 仅告警，生产环境建议按团队 SLA 收紧）"
          '''
        }
      }
    }

    stage('Update GitOps Manifest') {
      steps {
        withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
          sh '''
            set -eu
            rm -rf /tmp/gitops
            git clone -q "https://x-access-token:${GITHUB_TOKEN}@${DEPLOY_REPO}" /tmp/gitops
            cd /tmp/gitops
            git config user.email "ci@lab.local"
            git config user.name  "jenkins-ci"

            echo "--- 按环境写清单：dev 写 tag，prod 写 digest ---"
            sh "$WORKSPACE/ci/update-manifest.sh" "$OVERLAY"

            git --no-pager diff --stat
            git commit -am "ci($DEPLOY_ENV): springboot-demo -> $TAG" || {
              echo "清单无变化（同一 tag 重复构建），跳过提交"
              exit 0
            }
            git -c http.version=HTTP/1.1 push origin main
          '''
        }
      }
    }

    stage('Sync Argo CD (dev)') {
      when {
        allOf {
          expression { params.DEPLOY_ENV == 'dev' }
          expression { params.AUTO_SYNC }
        }
      }
      steps {
        withCredentials([string(credentialsId: 'argocd-token', variable: 'ARGO_TOKEN')]) {
          sh '''
            set -eu
            echo "--- 触发 Argo CD 同步（代替 GitHub webhook：集群在内网，GitHub 打不进来）---"
            curl -sk -X POST -H "Authorization: Bearer $ARGO_TOKEN" \
              "$ARGO_URL/api/v1/applications/$ARGO_APP/sync" \
              -H 'Content-Type: application/json' -d '{"prune":true}' | head -c 300
            echo

            for i in $(seq 1 60); do
              st=$(curl -sk -H "Authorization: Bearer $ARGO_TOKEN" "$ARGO_URL/api/v1/applications/$ARGO_APP" \
                   | grep -o '"status":"[A-Za-z]*"' | head -1 | cut -d'"' -f4)
              ph=$(curl -sk -H "Authorization: Bearer $ARGO_TOKEN" "$ARGO_URL/api/v1/applications/$ARGO_APP" \
                   | grep -o '"phase":"[A-Za-z]*"' | head -1 | cut -d'"' -f4)
              echo "同步状态 sync=$st phase=$ph（第 $i 次）"
              [ "$st" = "Synced" ] && [ "$ph" = "Succeeded" ] && exit 0
              sleep 10
            done
            echo "等待 Argo 同步超时，请到 Argo CD 界面确认"; exit 1
          '''
        }
      }
    }

    stage('Verify Rollout (dev)') {
      when {
        allOf {
          expression { params.DEPLOY_ENV == 'dev' }
          expression { params.AUTO_SYNC }
        }
      }
      steps {
        echo '--- 冒烟验证：Ingress 首页 + 读写分离接口（走 output 观察是否切到新镜像）---'
        sh '''
          set -eu
          for i in $(seq 1 30); do
            out=$(curl -s -k -H 'Host: demo-dev.local' "http://172.17.0.1:30880/api/users/ping/datasource" || true)
            echo "$out" | grep -q readDatasource && break
            echo "接口尚未就绪，等待中…（第 $i 次）"
            sleep 10
          done
          echo "$out"
          echo "$out" | grep -q '"readDatasource":"slave"' || { echo "读写分离接口返回异常"; exit 1; }
          curl -s -k -H 'Host: demo-dev.local' "http://172.17.0.1:30880/" | grep -q "用户管理" || { echo "页面不可访问"; exit 1; }
          echo "冒烟验证通过"
        '''
      }
    }
  }

  post {
    success {
      echo "✅ ${params.DEPLOY_ENV} 环境构建成功：${env.TAG}"
    }
    failure {
      echo "❌ 构建失败，请查看上方 stage 日志定位"
    }
  }
}
