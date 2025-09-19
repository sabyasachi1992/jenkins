pipeline {
  agent any

  parameters {
    string(name: 'IMAGE_TAG_OVERRIDE', defaultValue: '', description: 'Optional: custom image tag (default = short commit SHA from app repo)')
    booleanParam(name: 'FORCE_ROLLOUT', defaultValue: false, description: 'Trigger App Runner rollout (only if auto-deploy is OFF)')
  }

  environment {
    // -------- App repo (private) --------
    CODE_REPO_URL = 'https://github.com/sabyasachi1992/methane-detection.git'
    CODE_BRANCH   = 'main'
    APP_DIR       = 'methane-detection'   // clone target folder

    // -------- AWS / ECR --------
    AWS_ACCESS_KEY_ID     = credentials('AWS_ACCESS_KEY_ID')
    AWS_SECRET_ACCESS_KEY = credentials('AWS_SECRET_ACCESS_KEY')
    AWS_DEFAULT_REGION    = credentials('AWS_DEFAULT_REGION')   // e.g., ap-south-1
    AWS_ACCOUNT_ID        = '194722403970'
    IMAGE_REPO_NAME       = 'methane-tracker'
    REPO_URI              = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_DEFAULT_REGION}.amazonaws.com/${IMAGE_REPO_NAME}"

    // Optional: only if you plan to force rollout with auto-deploy disabled
    // APPRUNNER_SERVICE_ARN = credentials('APPRUNNER_SERVICE_ARN')

    // Build ergonomics
    DOCKER_BUILDKIT = '1'

    // AWS CLI image
    AWSCLI_IMAGE = 'amazon/aws-cli:latest'

    // Optional corporate CA bundle (Secret file). If you create it, uncomment this line:
    // AWS_CA_BUNDLE = credentials('AWS_CA_BUNDLE_PEM')
  }

  options {
    timestamps()
  }

  stages {

    stage('Prep workspace') {
      steps {
        echo '==================== 🧹 PREP WORKSPACE ===================='
        deleteDir()
        sh 'pwd && ls -la'
      }
    }

    stage('Checkout app (private via PAT)') {
      steps {
        echo '==================== 📥 CHECKOUT APP ======================='
        withCredentials([string(credentialsId: 'GIT_PAT', variable: 'GIT_PAT')]) {
          sh '''
            set -e
            export GIT_TERMINAL_PROMPT=0
            set +x  # hide PAT in logs
            git -c http.sslVerify=false clone --depth 1 --branch "${CODE_BRANCH}" "https://x-access-token:${GIT_PAT}@github.com/sabyasachi1992/methane-detection.git" "${APP_DIR}"
            set -x
            test -f "${APP_DIR}/Dockerfile" || (echo "ERROR: ${APP_DIR}/Dockerfile not found" && exit 1)
          '''
        }
        sh 'ls -la "${APP_DIR}"'
      }
    }

    stage('Tool check') {
      steps {
        echo '==================== 🔧 TOOL CHECK ========================='
        sh '''
          set -e
          echo "[docker] version:"
          docker version
        '''
      }
    }

    stage('Pull AWS CLI image') {
      steps {
        echo '==================== ⬇️  PULL AWS CLI ======================='
        sh '''
          set -e
          docker pull ${AWSCLI_IMAGE}
          docker run --rm ${AWSCLI_IMAGE} --version
        '''
      }
    }

    stage('Compute image tag') {
      steps {
        echo '==================== 🏷️  IMAGE TAG ========================='
        script {
          def sha = sh(script: "cd ${env.APP_DIR} && git rev-parse --short HEAD || echo init", returnStdout: true).trim()
          env.UNIQUE_TAG = params.IMAGE_TAG_OVERRIDE?.trim() ? params.IMAGE_TAG_OVERRIDE.trim() : sha
          echo "Using image tag: ${env.UNIQUE_TAG}"
        }
      }
    }

    stage('Login to ECR (via awscli container)') {
      steps {
        echo '==================== 🔐 ECR LOGIN =========================='
        sh '''
          set -e
          # Build optional CA flag
          EXTRA_CA=""
          if [ -n "${AWS_CA_BUNDLE:-}" ]; then
            EXTRA_CA="-e AWS_CA_BUNDLE=${AWS_CA_BUNDLE}"
          fi

          # Get ECR password with containerized awscli, then login host docker
          docker run --rm \
            -e AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID}" \
            -e AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY}" \
            -e AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION}" \
            $EXTRA_CA \
            ${AWSCLI_IMAGE} ecr get-login-password --region "${AWS_DEFAULT_REGION}" \
          | docker login --username AWS --password-stdin "${REPO_URI}"
        '''
      }
    }

    stage('Build image') {
      steps {
        echo '==================== 🧱 DOCKER BUILD ======================='
        sh '''
          set -e
          echo "Building ${IMAGE_REPO_NAME}:${UNIQUE_TAG} and :latest from ./${APP_DIR}"
          docker build -f "${APP_DIR}/Dockerfile" -t "${IMAGE_REPO_NAME}:${UNIQUE_TAG}" -t "${IMAGE_REPO_NAME}:latest" "${APP_DIR}"
        '''
      }
    }

    stage('Tag & push to ECR') {
      steps {
        echo '==================== 🚀 PUSH TO ECR ========================'
        sh '''
          set -e
          docker tag  "${IMAGE_REPO_NAME}:${UNIQUE_TAG}" "${REPO_URI}:${UNIQUE_TAG}"
          docker tag  "${IMAGE_REPO_NAME}:${UNIQUE_TAG}" "${REPO_URI}:latest"
          docker push "${REPO_URI}:${UNIQUE_TAG}"
          docker push "${REPO_URI}:latest"
        '''
      }
    }

    stage('Force App Runner rollout (optional)') {
      when { expression { return params.FORCE_ROLLOUT } }
      steps {
        echo '==================== 🔁 APP RUNNER ROLLOUT ================='
        sh '''
          set -e
          if [ -z "${APPRUNNER_SERVICE_ARN:-}" ]; then
            echo "APPRUNNER_SERVICE_ARN not configured; skipping manual rollout."
            exit 0
          fi

          EXTRA_CA=""
          if [ -n "${AWS_CA_BUNDLE:-}" ]; then
            EXTRA_CA="-e AWS_CA_BUNDLE=${AWS_CA_BUNDLE}"
          fi

          docker run --rm \
            -e AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID}" \
            -e AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY}" \
            -e AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION}" \
            $EXTRA_CA \
            ${AWSCLI_IMAGE} apprunner start-deployment --service-arn "${APPRUNNER_SERVICE_ARN}"
        '''
      }
    }
  }

  post {
    always {
      echo '==================== 🧽 CLEANUP ============================'
      sh 'docker image prune -f || true'
    }
    success {
      echo "✅ Built & pushed: ${REPO_URI}:${UNIQUE_TAG} and :latest"
      echo "ℹ️ With App Runner AutoDeployments ON, rollout happens automatically."
    }
    failure {
      echo "❌ Pipeline failed. Check stage logs above."
    }
  }
}
