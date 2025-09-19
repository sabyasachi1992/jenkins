pipeline {
  agent any

  parameters {
    string(name: 'IMAGE_TAG_OVERRIDE', defaultValue: '', description: 'Optional: set a custom image tag; default = short commit SHA')
    booleanParam(name: 'FORCE_ROLLOUT', defaultValue: false, description: 'If App Runner auto-deploy is OFF, tick to trigger rollout via CLI')
  }

  environment {
    // AWS creds (create these 3 as Jenkins "Secret Text" credentials)
    AWS_ACCESS_KEY_ID     = credentials('AWS_ACCESS_KEY_ID')
    AWS_SECRET_ACCESS_KEY = credentials('AWS_SECRET_ACCESS_KEY')
    AWS_DEFAULT_REGION    = credentials('AWS_DEFAULT_REGION') // e.g., ap-south-1

    // Optional: corporate root CA bundle if needed (set as a Jenkins File credential id, then uncomment)
    // AWS_CA_BUNDLE        = credentials('AWS_CA_BUNDLE_PEM')

    // ECR / image
    AWS_ACCOUNT_ID  = '194722403970'
    IMAGE_REPO_NAME = 'methane-tracker'
    REPO_URI        = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_DEFAULT_REGION}.amazonaws.com/${IMAGE_REPO_NAME}"

    // Optional: App Runner service ARN credential (only needed if FORCE_ROLLOUT=true and auto-deploy is disabled)
    // APPRUNNER_SERVICE_ARN = credentials('APPRUNNER_SERVICE_ARN')

    // Build ergonomics
    DOCKER_BUILDKIT = '1'
  }

  options {
    timestamps()
    ansiColor('xterm')
  }

  stages {
    stage('Checkout') {
      steps {
        checkout scm
        sh 'git --version || true'
      }
    }

    stage('Compute Tag') {
      steps {
        script {
          def sha = sh(script: 'git rev-parse --short HEAD || echo init', returnStdout: true).trim()
          env.UNIQUE_TAG = params.IMAGE_TAG_OVERRIDE?.trim() ? params.IMAGE_TAG_OVERRIDE.trim() : sha
          echo "Using image tag: ${env.UNIQUE_TAG}"
        }
      }
    }

    stage('ECR Login') {
      steps {
        sh '''
          set -e
          aws --version || true
          # If you use a corporate CA, uncomment next line:
          # export AWS_CA_BUNDLE="${AWS_CA_BUNDLE}"
          aws ecr get-login-password --region "$AWS_DEFAULT_REGION" | docker login --username AWS --password-stdin "$REPO_URI"
        '''
      }
    }

    stage('Build Image') {
      steps {
        sh '''
          set -e
          echo "Building Docker image ${IMAGE_REPO_NAME}:${UNIQUE_TAG} and :latest"
          docker build -t "${IMAGE_REPO_NAME}:${UNIQUE_TAG}" -t "${IMAGE_REPO_NAME}:latest" .
        '''
      }
    }

    stage('Tag & Push to ECR') {
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

    stage('Force App Runner Rollout (optional)') {
      when { expression { return params.FORCE_ROLLOUT } }
      steps {
        sh '''
          set -e
          if [ -z "${APPRUNNER_SERVICE_ARN:-}" ]; then
            echo "APPRUNNER_SERVICE_ARN not configured in Jenkins credentials; skipping manual rollout."
            exit 0
          fi
          echo "Triggering App Runner deployment for ${APPRUNNER_SERVICE_ARN}..."
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
      echo "✅ Pushed ${REPO_URI}:${UNIQUE_TAG} and ${REPO_URI}:latest"
      echo "If App Runner AutoDeployments is ON, the service will update automatically."
    }
    failure {
      echo "❌ Pipeline failed. Check the stage logs above."
    }
  }
}
