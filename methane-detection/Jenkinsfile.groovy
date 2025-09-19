// pipeline {
//   agent any

//   parameters {
//     string(name: 'IMAGE_TAG_OVERRIDE', defaultValue: '', description: 'Optional: custom image tag (default = short commit SHA from app repo)')
//     booleanParam(name: 'FORCE_ROLLOUT', defaultValue: false, description: 'Trigger App Runner rollout (only if auto-deploy is OFF)')
//   }

//   environment {
//     // -------- App repo (private) --------
//     CODE_REPO_URL = 'https://github.com/sabyasachi1992/methane-detection.git'
//     CODE_BRANCH   = 'main'
//     APP_DIR       = 'methane-detection'   // clone target folder

//     // -------- AWS / ECR --------
//     AWS_ACCESS_KEY_ID     = credentials('AWS_ACCESS_KEY_ID')
//     AWS_SECRET_ACCESS_KEY = credentials('AWS_SECRET_ACCESS_KEY')
//     AWS_DEFAULT_REGION    = credentials('AWS_DEFAULT_REGION')   // e.g., ap-south-1
//     AWS_ACCOUNT_ID        = '194722403970'
//     IMAGE_REPO_NAME       = 'methane-tracker'
//     REPO_URI              = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_DEFAULT_REGION}.amazonaws.com/${IMAGE_REPO_NAME}"

//     // Optional: only if you plan to force rollout with auto-deploy disabled
//     // APPRUNNER_SERVICE_ARN = credentials('APPRUNNER_SERVICE_ARN')

//     // AWS CLI container
//     AWSCLI_IMAGE = 'amazon/aws-cli:latest'

//     // Do NOT force BuildKit globally (we’ll decide at build time)
//     // DOCKER_BUILDKIT = '1'
//   }

//   options {
//     timestamps()
//   }

//   stages {

//     stage('Prep workspace') {
//       steps {
//         echo '==================== 🧹 PREP WORKSPACE ===================='
//         deleteDir()
//         sh 'pwd && ls -la'
//       }
//     }

//     stage('Checkout app (private via PAT)') {
//       steps {
//         echo '==================== 📥 CHECKOUT APP ======================='
//         withCredentials([string(credentialsId: 'GIT_PAT', variable: 'GIT_PAT')]) {
//           sh '''
//             set -e
//             export GIT_TERMINAL_PROMPT=0
//             set +x  # hide PAT in logs
//             git -c http.sslVerify=false clone --depth 1 --branch "${CODE_BRANCH}" "https://x-access-token:${GIT_PAT}@github.com/sabyasachi1992/methane-detection.git" "${APP_DIR}"
//             set -x
//             test -f "${APP_DIR}/Dockerfile" || (echo "ERROR: ${APP_DIR}/Dockerfile not found" && exit 1)
//           '''
//         }
//         sh 'ls -la "${APP_DIR}"'
//       }
//     }

//     stage('Tool check') {
//       steps {
//         echo '==================== 🔧 TOOL CHECK ========================='
//         sh '''
//           set -e
//           echo "[docker] version:"
//           docker version
//           echo "[docker buildx] check:"
//           if docker buildx version >/dev/null 2>&1; then
//             echo "✅ buildx present"
//           else
//             echo "ℹ️  buildx NOT installed; will use classic builder"
//           fi
//         '''
//       }
//     }

//     stage('Pull AWS CLI image') {
//       steps {
//         echo '==================== ⬇️  PULL AWS CLI ======================='
//         sh '''
//           set -e
//           docker pull ${AWSCLI_IMAGE}
//           docker run --rm ${AWSCLI_IMAGE} --version
//         '''
//       }
//     }

//     stage('Compute image tag') {
//       steps {
//         echo '==================== 🏷️  IMAGE TAG ========================='
//         script {
//           def sha = sh(script: "cd ${env.APP_DIR} && git rev-parse --short HEAD || echo init", returnStdout: true).trim()
//           env.UNIQUE_TAG = params.IMAGE_TAG_OVERRIDE?.trim() ? params.IMAGE_TAG_OVERRIDE.trim() : sha
//           echo "Using image tag: ${env.UNIQUE_TAG}"
//         }
//       }
//     }

//     stage('Login to ECR (via awscli container)') {
//       steps {
//         echo '==================== 🔐 ECR LOGIN =========================='
//         sh '''
//           set -e
//           EXTRA_CA=""
//           if [ -n "${AWS_CA_BUNDLE:-}" ]; then
//             EXTRA_CA="-e AWS_CA_BUNDLE=${AWS_CA_BUNDLE}"
//           fi

//           docker run --rm \
//             -e AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID}" \
//             -e AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY}" \
//             -e AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION}" \
//             $EXTRA_CA \
//             ${AWSCLI_IMAGE} ecr get-login-password --region "${AWS_DEFAULT_REGION}" \
//           | docker login --username AWS --password-stdin "${REPO_URI}"
//         '''
//       }
//     }

//     stage('Build image') {
//       steps {
//         echo '==================== 🧱 DOCKER BUILD ======================='
//         sh '''
//           set -e
//           # Decide BuildKit dynamically
//           if docker buildx version >/dev/null 2>&1; then
//             echo "🔧 Using BuildKit (buildx detected)"
//             export DOCKER_BUILDKIT=1
//           else
//             echo "🔧 Using classic builder (no buildx)"
//             export DOCKER_BUILDKIT=0
//           fi

//           echo "Building ${IMAGE_REPO_NAME}:${UNIQUE_TAG} and :latest from ./${APP_DIR}"
//           docker build -f "${APP_DIR}/Dockerfile" -t "${IMAGE_REPO_NAME}:${UNIQUE_TAG}" -t "${IMAGE_REPO_NAME}:latest" "${APP_DIR}"
//         '''
//       }
//     }

//     stage('Tag & push to ECR') {
//       steps {
//         echo '==================== 🚀 PUSH TO ECR ========================'
//         sh '''
//           set -e
//           docker tag  "${IMAGE_REPO_NAME}:${UNIQUE_TAG}" "${REPO_URI}:${UNIQUE_TAG}"
//           docker tag  "${IMAGE_REPO_NAME}:${UNIQUE_TAG}" "${REPO_URI}:latest"
//           docker push "${REPO_URI}:${UNIQUE_TAG}"
//           docker push "${REPO_URI}:latest"
//         '''
//       }
//     }

//     stage('Force App Runner rollout (optional)') {
//       when { expression { return params.FORCE_ROLLOUT } }
//       steps {
//         echo '==================== 🔁 APP RUNNER ROLLOUT ================='
//         sh '''
//           set -e
//           if [ -z "${APPRUNNER_SERVICE_ARN:-}" ]; then
//             echo "APPRUNNER_SERVICE_ARN not configured; skipping manual rollout."
//             exit 0
//           fi

//           EXTRA_CA=""
//           if [ -n "${AWS_CA_BUNDLE:-}" ]; then
//             EXTRA_CA="-e AWS_CA_BUNDLE=${AWS_CA_BUNDLE}"
//           fi

//           docker run --rm \
//             -e AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID}" \
//             -e AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY}" \
//             -e AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION}" \
//             $EXTRA_CA \
//             ${AWSCLI_IMAGE} apprunner start-deployment --service-arn "${APPRUNNER_SERVICE_ARN}"
//         '''
//       }
//     }
//   }

//   post {
//     always {
//       echo '==================== 🧽 CLEANUP ============================'
//       sh 'docker image prune -f || true'
//     }
//     success {
//       echo "✅ Built & pushed: ${REPO_URI}:${UNIQUE_TAG} and :latest"
//       echo "ℹ️ With App Runner AutoDeployments ON, rollout happens automatically."
//     }
//     failure {
//       echo "❌ Pipeline failed. Check stage logs above."
//     }
//   }
// }


pipeline {
  agent any

  parameters {
    string(name: 'SERVICE_NAME', defaultValue: 'methane-tracker-v2', description: 'App Runner service name to create/update')
    booleanParam(name: 'RECREATE_SERVICE', defaultValue: true, description: 'Delete existing service with the same name before creating a new one')
    booleanParam(name: 'AUTO_DEPLOY', defaultValue: true, description: 'Enable App Runner AutoDeployments from ECR')
    string(name: 'ENTRY_FILE', defaultValue: 'scientific_methane_app.py', description: 'Streamlit entry file (use enhanced_scientific_methane_app.py for enhanced UI)')
    string(name: 'EE_PROJECT', defaultValue: '', description: 'EE project (env var for app)')
    string(name: 'OPENEO_EMAIL', defaultValue: '', description: 'OpenEO username/email')
    string(name: 'COPERNICUS_USERNAME', defaultValue: '', description: 'Copernicus username')
    string(name: 'IMAGE_TAG_OVERRIDE', defaultValue: '', description: 'Optional: custom image tag (default = short commit SHA)')
    booleanParam(name: 'FORCE_ROLLOUT', defaultValue: false, description: 'Trigger rollout now (only used when AUTO_DEPLOY=false)')
  }

  environment {
    // -------- Repos --------
    CODE_REPO_URL = 'https://github.com/sabyasachi1992/methane-detection.git'
    CODE_BRANCH   = 'main'
    APP_DIR       = 'methane-detection'

    // -------- AWS / ECR --------
    AWS_ACCESS_KEY_ID     = credentials('AWS_ACCESS_KEY_ID')
    AWS_SECRET_ACCESS_KEY = credentials('AWS_SECRET_ACCESS_KEY')
    AWS_DEFAULT_REGION    = credentials('AWS_DEFAULT_REGION')   // e.g., ap-south-1
    AWS_ACCOUNT_ID        = '194722403970'
    IMAGE_REPO_NAME       = 'methane-tracker'
    REPO_URI              = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_DEFAULT_REGION}.amazonaws.com/${IMAGE_REPO_NAME}"

    // AWS CLI container
    AWSCLI_IMAGE = 'amazon/aws-cli:latest'

    // Secrets (names in Secrets Manager)
    SECRET_GOOGLE  = 'GOOGLE_CREDENTIALS'
    SECRET_OPENEO  = 'OPENEO_PASSWORD'
    SECRET_COPERNI = 'COPERNICUS_PASSWORD'

    // Roles (we’ll fetch ARNs by name)
    ECR_ROLE_NAME = 'AppRunnerEcrAccessRole'
    RT_ROLE_NAME  = 'AppRunnerRuntimeRole'
  }

  options { timestamps() }

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
            set +x
            git -c http.sslVerify=false clone --depth 1 --branch "${CODE_BRANCH}" "https://x-access-token:${GIT_PAT}@github.com/sabyasachi1992/methane-detection.git" "${APP_DIR}"
            set -x
            test -f "${APP_DIR}/Dockerfile" || (echo "ERROR: ${APP_DIR}/Dockerfile not found" && exit 1)
          '''
        }
        sh 'ls -la "${APP_DIR}"'
      }
    }

    stage('Tool check + Pull AWS CLI image') {
      steps {
        echo '==================== 🔧 TOOL CHECK / PULL AWSCLI ==========='
        sh '''
          set -e
          docker version
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

    stage('Login to ECR') {
      steps {
        echo '==================== 🔐 ECR LOGIN =========================='
        sh '''
          set -e
          docker run --rm \
            -e AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID}" \
            -e AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY}" \
            -e AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION}" \
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
          if docker buildx version >/dev/null 2>&1; then
            echo "🔧 Using BuildKit (buildx detected)"; export DOCKER_BUILDKIT=1
          else
            echo "🔧 Using classic builder (no buildx)"; export DOCKER_BUILDKIT=0
          fi
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

    stage('Create / Recreate App Runner service') {
      steps {
        echo '==================== 🧭 APP RUNNER SERVICE ================='
        script {
          def img = "${REPO_URI}:latest"

          sh """
            set -e

            # Resolve role ARNs
            ECR_ROLE_ARN=\$(docker run --rm -e AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} -e AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} -e AWS_DEFAULT_REGION=${AWS_DEFAULT_REGION} ${AWSCLI_IMAGE} iam get-role --role-name ${ECR_ROLE_NAME} --query 'Role.Arn' --output text)
            RT_ROLE_ARN=\$(docker run --rm -e AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} -e AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} -e AWS_DEFAULT_REGION=${AWS_DEFAULT_REGION} ${AWSCLI_IMAGE} iam get-role --role-name ${RT_ROLE_NAME}  --query 'Role.Arn' --output text)

            echo "[Roles]"
            echo "ECR role: \$ECR_ROLE_ARN"
            echo "RT  role: \$RT_ROLE_ARN"

            # Find existing service ARN (if any)
            SVC_ARN=\$(docker run --rm -e AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} -e AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} -e AWS_DEFAULT_REGION=${AWS_DEFAULT_REGION} ${AWSCLI_IMAGE} apprunner list-services --query "ServiceSummaryList[?ServiceName=='${SERVICE_NAME}'].ServiceArn" --output text || true)

            if [ -n "\$SVC_ARN" ] && [ "${RECREATE_SERVICE}" = "true" ]; then
              echo "🔴 Deleting existing service ${SERVICE_NAME} (\$SVC_ARN)"
              docker run --rm -e AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} -e AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} -e AWS_DEFAULT_REGION=${AWS_DEFAULT_REGION} ${AWSCLI_IMAGE} apprunner delete-service --service-arn "\$SVC_ARN" >/dev/null

              # Wait until service disappears
              echo "⏳ Waiting for deletion to complete..."
              for i in \$(seq 1 60); do
                sleep 10
                CUR=\$(docker run --rm -e AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} -e AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} -e AWS_DEFAULT_REGION=${AWS_DEFAULT_REGION} ${AWSCLI_IMAGE} apprunner list-services --query "ServiceSummaryList[?ServiceName=='${SERVICE_NAME}'].ServiceArn" --output text || true)
                [ -z "\$CUR" ] && break
                echo "  still deleting..."
              done
              echo "✅ Old service removed (or not found)."
              SVC_ARN=""
            fi

            if [ -z "\$SVC_ARN" ]; then
              echo "🟢 Creating new App Runner service: ${SERVICE_NAME}"
              docker run --rm \
                -e AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} \
                -e AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} \
                -e AWS_DEFAULT_REGION=${AWS_DEFAULT_REGION} \
                ${AWSCLI_IMAGE} apprunner create-service \
                --service-name "${SERVICE_NAME}" \
                --source-configuration "ImageRepository={ImageIdentifier=${img},ImageRepositoryType=ECR,ImageConfiguration={Port=8080,RuntimeEnvironmentVariables=[{Name=ENTRY_FILE,Value=${ENTRY_FILE}},{Name=EE_PROJECT,Value=${EE_PROJECT}},{Name=OPENEO_EMAIL,Value=${OPENEO_EMAIL}},{Name=COPERNICUS_USERNAME,Value=${COPERNICUS_USERNAME}}],RuntimeEnvironmentSecrets=[{Name=GOOGLE_CREDENTIALS,Value=arn:aws:secretsmanager:${AWS_DEFAULT_REGION}:${AWS_ACCOUNT_ID}:secret:${SECRET_GOOGLE}},{Name=OPENEO_PASSWORD,Value=arn:aws:secretsmanager:${AWS_DEFAULT_REGION}:${AWS_ACCOUNT_ID}:secret:${SECRET_OPENEO}},{Name=COPERNICUS_PASSWORD,Value=arn:aws:secretsmanager:${AWS_DEFAULT_REGION}:${AWS_ACCOUNT_ID}:secret:${SECRET_COPERNI}}]},AccessRoleArn=\$ECR_ROLE_ARN},AutoDeploymentsEnabled=${AUTO_DEPLOY}" \
                --instance-configuration "Cpu=1 vCPU,Memory=2 GB,InstanceRoleArn=\$RT_ROLE_ARN" >/dev/null

              # capture new ARN
              SVC_ARN=\$(docker run --rm -e AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} -e AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} -e AWS_DEFAULT_REGION=${AWS_DEFAULT_REGION} ${AWSCLI_IMAGE} apprunner list-services --query "ServiceSummaryList[?ServiceName=='${SERVICE_NAME}'].ServiceArn" --output text)

            else
              echo "🟡 Service exists. Updating image/config..."
              docker run --rm \
                -e AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} \
                -e AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} \
                -e AWS_DEFAULT_REGION=${AWS_DEFAULT_REGION} \
                ${AWSCLI_IMAGE} apprunner update-service \
                --service-arn "\$SVC_ARN" \
                --source-configuration "ImageRepository={ImageRepositoryType=ECR,ImageIdentifier=${img},ImageConfiguration={Port=8080,RuntimeEnvironmentVariables=[{Name=ENTRY_FILE,Value=${ENTRY_FILE}},{Name=EE_PROJECT,Value=${EE_PROJECT}},{Name=OPENEO_EMAIL,Value=${OPENEO_EMAIL}},{Name=COPERNICUS_USERNAME,Value=${COPERNICUS_USERNAME}}],RuntimeEnvironmentSecrets=[{Name=GOOGLE_CREDENTIALS,Value=arn:aws:secretsmanager:${AWS_DEFAULT_REGION}:${AWS_ACCOUNT_ID}:secret:${SECRET_GOOGLE}},{Name=OPENEO_PASSWORD,Value=arn:aws:secretsmanager:${AWS_DEFAULT_REGION}:${AWS_ACCOUNT_ID}:secret:${SECRET_OPENEO}},{Name=COPERNICUS_PASSWORD,Value=arn:aws:secretsmanager:${AWS_DEFAULT_REGION}:${AWS_ACCOUNT_ID}:secret:${SECRET_COPERNI}}]}" \
                ${AUTO_DEPLOY == true ? '--auto-deployments-enabled' : '--no-auto-deployments-enabled'} >/dev/null
            fi

            echo "⏳ Waiting until service is RUNNING..."
            for i in \$(seq 1 60); do
              STATUS=\$(docker run --rm -e AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} -e AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} -e AWS_DEFAULT_REGION=${AWS_DEFAULT_REGION} ${AWSCLI_IMAGE} apprunner describe-service --service-arn "\$SVC_ARN" --query "Service.Status" --output text)
              echo "  status: \$STATUS"
              [ "\$STATUS" = "RUNNING" ] && break
              sleep 10
            done

            URL=\$(docker run --rm -e AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} -e AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} -e AWS_DEFAULT_REGION=${AWS_DEFAULT_REGION} ${AWSCLI_IMAGE} apprunner describe-service --service-arn "\$SVC_ARN" --query "Service.ServiceUrl" --output text)
            echo "✅ Service URL: \$URL"
          """
        }
      }
    }

    stage('Optional manual rollout (if AUTO_DEPLOY=false)') {
      when { expression { return params.FORCE_ROLLOUT && !params.AUTO_DEPLOY } }
      steps {
        echo '==================== 🔁 MANUAL ROLLOUT ====================='
        sh '''
          set -e
          SVC_ARN=$(docker run --rm -e AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} -e AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} -e AWS_DEFAULT_REGION=${AWS_DEFAULT_REGION} ${AWSCLI_IMAGE} apprunner list-services --query "ServiceSummaryList[?ServiceName=='${SERVICE_NAME}'].ServiceArn" --output text)
          docker run --rm \
            -e AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} \
            -e AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} \
            -e AWS_DEFAULT_REGION=${AWS_DEFAULT_REGION} \
            ${AWSCLI_IMAGE} apprunner start-deployment --service-arn "$SVC_ARN"
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
      echo "✅ Image pushed: ${REPO_URI}:${UNIQUE_TAG} and :latest"
      echo "✅ App Runner service ensured: ${params.SERVICE_NAME}"
    }
    failure {
      echo "❌ Pipeline failed. Check stage logs above."
    }
  }
}
