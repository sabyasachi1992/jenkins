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
    APP_DIR       = 'methane-detection'   // where we’ll clone the app

    // -------- AWS / ECR --------
    AWS_ACCESS_KEY_ID     = credentials('AWS_ACCESS_KEY_ID')
    AWS_SECRET_ACCESS_KEY = credentials('AWS_SECRET_ACCESS_KEY')
    AWS_DEFAULT_REGION    = credentials('AWS_DEFAULT_REGION')   // ap-south-1
    AWS_ACCOUNT_ID        = '194722403970'
    IMAGE_REPO_NAME       = 'methane-tracker'
    REPO_URI              = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_DEFAULT_REGION}.amazonaws.com/${IMAGE_REPO_NAME}"

    // (Optional) Only needed if you’ll manually trigger rollout when auto-deploy is disabled
    // APPRUNNER_SERVICE_ARN = credentials('APPRUNNER_SERVICE_ARN')

    // Build ergonomics
    DOCKER_BUILDKIT = '1'
  }

  options {
    timestamps()
    ansiColor('xterm')
  }

  stages {
    stage('Prep workspace') {
      steps {
        deleteDir()
        sh 'pwd && ls -la'
      }
    }

    stage('Checkout app (private via PAT)') {
      steps {
        withCredentials([string(credentialsId: 'GIT_PAT', variable: 'GIT_PAT')]) {
          sh '''
            set -e
            set +x  # hide PAT
            git -c http.extraHeader="Authorization: Bearer ${GIT_PAT}" clone --depth 1 --branch "${CODE_BRANCH}" "${CODE_REPO_URL}" "${APP_DIR}"
            set -x
            test -f "${APP_DIR}/Dockerfile" || (echo "ERROR: ${APP_DIR}/Dockerfile not found" && exit 1)
          '''
        }
      }
    }

    stage('Compute image tag') {
      steps {
        script {
          // get short SHA from the app repo
          def sha = sh(script: "cd ${env.APP_DIR} && git rev-parse --short HEAD || echo init", returnStdout: true).trim()
          env.UNIQUE_TAG = params.IMAGE_TAG_OVERRIDE?.trim() ? params.IMAGE_TAG_OVERRIDE.trim() : sha
          echo "Using image tag: ${env.UNIQUE_TAG}"
        }
      }
    }

    stage('Login to ECR') {
      steps {
        sh '''
          set -e
          aws --version || true
          aws ecr get-login-password --region "$AWS_DEFAULT_REGION" | docker login --username AWS --password-stdin "$REPO_URI"
        '''
      }
    }

    stage('Build image') {
      steps {
        sh '''
          set -e
          echo "Building ${IMAGE_REPO_NAME}:${UNIQUE_TAG} and :latest from ./${APP_DIR}"
          docker build -f "${APP_DIR}/Dockerfile" -t "${IMAGE_REPO_NAME}:${UNIQUE_TAG}" -t "${IMAGE_REPO_NAME}:latest" "${APP_DIR}"
        '''
      }
    }

    stage('Tag & push to ECR') {
      steps {
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
        sh '''
          set -e
          if [ -z "${APPRUNNER_SERVICE_ARN:-}" ]; then
            echo "APPRUNNER_SERVICE_ARN not configured; skipping manual rollout."
            exit 0
          fi
          echo "Triggering App Runner deployment..."
          aws apprunner start-deployment --service-arn "${APPRUNNER_SERVICE_ARN}"
        '''
      }
    }
  }

  post {
    always {
      sh 'docker image prune -f || true'
    }
    success {
      echo "✅ Built & pushed: ${REPO_URI}:${UNIQUE_TAG} and :latest"
      echo "If App Runner AutoDeployments is ON, rollout will happen automatically."
    }
    failure {
      echo "❌ Pipeline failed. Check the stage logs."
    }
  }
}
