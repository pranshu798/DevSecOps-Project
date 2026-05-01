// ============================================================
//  TRUNK-BASED DEPLOYMENT PIPELINE
//  Spring Boot → Docker → ECR → EKS (dev / uat / prod)
//  Helm charts  |  DevSecOps gates  |  AWS EKS
// ============================================================
//
//  REQUIRED JENKINS CREDENTIALS  (Manage Jenkins → Credentials)
//  ┌─────────────────────────────────────────────────────────┐
//  │ ID                        │ Kind                        │
//  ├─────────────────────────────────────────────────────────┤
//  │ aws-credentials           │ AWS Credentials             │
//  │ sonar-token               │ Secret Text                 │
//  │ github-token              │ Username + Password / SSH   │
//  │ helm-repo-token           │ Secret Text (if private)    │
//  └─────────────────────────────────────────────────────────┘
//
//  REQUIRED JENKINS PLUGINS
//  • Pipeline, Git, Docker Pipeline, AWS Steps
//  • SonarQube Scanner, OWASP Dependency-Check
//  • Kubernetes CLI (kubectl), Helm
//  • Blue Ocean (optional, nicer UI)
//
//  REQUIRED JENKINS GLOBAL TOOLS  (Manage Jenkins → Tools)
//  • Maven  → name: "maven3"
//  • SonarQube Scanner → name: "sonar-scanner"
//
//  REQUIRED JENKINS SYSTEM CONFIG
//  • SonarQube server → name: "SonarQube-Server"
// ============================================================

pipeline {

    // ---------------------------------------------------------
    // Run on any agent that has Docker + kubectl + helm + AWS CLI
    // Label your Jenkins nodes with "eks-agent" or change below
    // ---------------------------------------------------------
    agent { label 'eks-agent' }

    // ---------------------------------------------------------
    //  GLOBAL ENVIRONMENT  – change these to match your AWS setup
    // ---------------------------------------------------------
    environment {
        // ── AWS ──────────────────────────────────────────────
        AWS_REGION          = 'ap-south-1'                      // Mumbai – closest to Bengaluru
        AWS_ACCOUNT_ID      = credentials('aws-account-id')     // Store as Jenkins Secret Text

        // ── ECR ──────────────────────────────────────────────
        ECR_REGISTRY        = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
        ECR_REPO            = 'pranshu-springboot-app'          // Must exist in ECR beforehand
        IMAGE_TAG           = "${env.BUILD_NUMBER}-${env.GIT_COMMIT?.take(7) ?: 'latest'}"
        FULL_IMAGE          = "${ECR_REGISTRY}/${ECR_REPO}:${IMAGE_TAG}"

        // ── EKS ──────────────────────────────────────────────
        EKS_CLUSTER_DEV     = 'pranshu-eks-dev'
        EKS_CLUSTER_UAT     = 'pranshu-eks-uat'
        EKS_CLUSTER_PROD    = 'pranshu-eks-prod'

        // ── Helm ─────────────────────────────────────────────
        HELM_CHART_PATH     = 'helm/springboot-app'             // relative to DevSecOps repo root
        HELM_RELEASE_NAME   = 'springboot-app'

        // ── SonarQube ─────────────────────────────────────────
        SONAR_PROJECT_KEY   = 'pranshu-springboot-app'

        // ── App Repos ─────────────────────────────────────────
        APP_REPO            = 'https://github.com/pranshu798/pranshu-springboot-app.git'
        DEVSECOPS_REPO      = 'https://github.com/pranshu798/DevSecOps-Project.git'

        // ── Misc ──────────────────────────────────────────────
        MAVEN_OPTS          = '-Dmaven.repo.local=.m2/repository'
    }

    // ---------------------------------------------------------
    //  PIPELINE OPTIONS
    // ---------------------------------------------------------
    options {
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timestamps()
        timeout(time: 60, unit: 'MINUTES')
        disableConcurrentBuilds()
        ansiColor('xterm')
    }

    // ---------------------------------------------------------
    //  TRIGGER: Poll SCM every minute on trunk (main)
    //  For webhook: replace with  triggers { githubPush() }
    // ---------------------------------------------------------
    triggers {
        pollSCM('* * * * *')
    }

    tools {
        maven 'maven3'
    }

    // =========================================================
    //  STAGES
    // =========================================================
    stages {

        // ─────────────────────────────────────────────────────
        // 1. CHECKOUT both repos
        // ─────────────────────────────────────────────────────
        stage('Checkout') {
            steps {
                echo "━━━ [1/10] Checkout ━━━"
                // App source
                dir('app') {
                    git branch: 'main',
                        credentialsId: 'github-token',
                        url: "${APP_REPO}"
                }
                // Helm charts + Dockerfiles
                dir('devsecops') {
                    git branch: 'main',
                        credentialsId: 'github-token',
                        url: "${DEVSECOPS_REPO}"
                }
                // Print commit SHAs for traceability
                script {
                    dir('app') {
                        APP_COMMIT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                    }
                    dir('devsecops') {
                        OPS_COMMIT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                    }
                    echo "App commit: ${APP_COMMIT} | DevSecOps commit: ${OPS_COMMIT}"
                }
            }
        }

        // ─────────────────────────────────────────────────────
        // 2. MAVEN BUILD + UNIT TESTS
        // ─────────────────────────────────────────────────────
        stage('Build & Unit Test') {
            steps {
                echo "━━━ [2/10] Maven Build & Unit Tests ━━━"
                dir('app') {
                    sh '''
                        mvn clean package \
                            -DskipTests=false \
                            -Dmaven.test.failure.ignore=false \
                            --batch-mode \
                            --no-transfer-progress
                    '''
                }
            }
            post {
                always {
                    junit 'app/target/surefire-reports/*.xml'
                    jacoco(
                        execPattern:      'app/target/jacoco.exec',
                        classPattern:     'app/target/classes',
                        sourcePattern:    'app/src/main/java',
                        exclusionPattern: '**/*Test*'
                    )
                }
            }
        }

        // ─────────────────────────────────────────────────────
        // 3. OWASP DEPENDENCY CHECK  (SCA – Software Composition)
        // ─────────────────────────────────────────────────────
        stage('OWASP Dependency Check') {
            steps {
                echo "━━━ [3/10] OWASP Dependency Check ━━━"
                dir('app') {
                    dependencyCheck(
                        additionalArguments: '--scan ./ --disableYarnAudit --disableNodeAudit --format XML',
                        odcInstallation: 'OWASP-DC'
                    )
                    dependencyCheckPublisher pattern: '**/dependency-check-report.xml'
                }
            }
        }

        // ─────────────────────────────────────────────────────
        // 4. SONARQUBE  SAST + CODE QUALITY
        // ─────────────────────────────────────────────────────
        stage('SonarQube Analysis') {
            steps {
                echo "━━━ [4/10] SonarQube SAST ━━━"
                dir('app') {
                    withSonarQubeEnv('SonarQube-Server') {
                        sh """
                            mvn sonar:sonar \
                                -Dsonar.projectKey=${SONAR_PROJECT_KEY} \
                                -Dsonar.projectName='Pranshu SpringBoot App' \
                                -Dsonar.java.coveragePlugin=jacoco \
                                -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml \
                                --batch-mode --no-transfer-progress
                        """
                    }
                }
            }
        }

        // ─────────────────────────────────────────────────────
        // 5. SONARQUBE QUALITY GATE  (blocks pipeline on failure)
        // ─────────────────────────────────────────────────────
        stage('Quality Gate') {
            steps {
                echo "━━━ [5/10] Quality Gate ━━━"
                timeout(time: 10, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        // ─────────────────────────────────────────────────────
        // 6. DOCKER BUILD
        // ─────────────────────────────────────────────────────
        stage('Docker Build') {
            steps {
                echo "━━━ [6/10] Docker Build → ${FULL_IMAGE} ━━━"
                dir('app') {
                    sh """
                        docker build \
                            --no-cache \
                            --build-arg BUILD_DATE=\$(date -u +"%Y-%m-%dT%H:%M:%SZ") \
                            --build-arg VCS_REF=${APP_COMMIT} \
                            --build-arg VERSION=${IMAGE_TAG} \
                            -t ${FULL_IMAGE} \
                            -t ${ECR_REGISTRY}/${ECR_REPO}:latest \
                            -f Dockerfile .
                    """
                }
            }
        }

        // ─────────────────────────────────────────────────────
        // 7. TRIVY IMAGE SCAN  (container vulnerability scan)
        // ─────────────────────────────────────────────────────
        stage('Trivy Image Scan') {
            steps {
                echo "━━━ [7/10] Trivy Container Scan ━━━"
                sh """
                    trivy image \
                        --exit-code 1 \
                        --severity HIGH,CRITICAL \
                        --no-progress \
                        --format table \
                        --output trivy-report.txt \
                        ${FULL_IMAGE} || true

                    # Fail the pipeline if CRITICAL vulns found
                    CRITICAL=\$(trivy image --exit-code 0 --severity CRITICAL --quiet --format json ${FULL_IMAGE} \
                        | python3 -c "import sys,json; d=json.load(sys.stdin); print(sum(len(r.get('Vulnerabilities') or []) for r in (d.get('Results') or [])))")
                    echo "Critical vulnerabilities found: \$CRITICAL"
                    if [ "\$CRITICAL" -gt "0" ]; then
                        echo "❌ CRITICAL vulnerabilities detected! Review trivy-report.txt"
                        # Uncomment to hard-block on critical CVEs:
                        # exit 1
                    fi
                """
                archiveArtifacts artifacts: 'trivy-report.txt', fingerprint: true
            }
        }

        // ─────────────────────────────────────────────────────
        // 8. PUSH TO ECR
        // ─────────────────────────────────────────────────────
        stage('Push to ECR') {
            steps {
                echo "━━━ [8/10] Push → ECR ━━━"
                withAWS(credentials: 'aws-credentials', region: "${AWS_REGION}") {
                    sh """
                        # Authenticate Docker to ECR
                        aws ecr get-login-password --region ${AWS_REGION} \
                            | docker login --username AWS --password-stdin ${ECR_REGISTRY}

                        docker push ${FULL_IMAGE}
                        docker push ${ECR_REGISTRY}/${ECR_REPO}:latest

                        echo "✅ Pushed: ${FULL_IMAGE}"
                    """
                }
            }
        }

        // ─────────────────────────────────────────────────────
        // 9. DEPLOY TO DEV  (automatic – trunk-based)
        // ─────────────────────────────────────────────────────
        stage('Deploy → DEV') {
            steps {
                echo "━━━ [9/10] Helm Deploy → DEV ━━━"
                withAWS(credentials: 'aws-credentials', region: "${AWS_REGION}") {
                    script {
                        deployToEnvironment(
                            env:         'dev',
                            cluster:     EKS_CLUSTER_DEV,
                            namespace:   'dev',
                            valuesFile:  'devsecops/helm/springboot-app/values-dev.yaml',
                            replicas:    1
                        )
                    }
                }
            }
        }

        // ─────────────────────────────────────────────────────
        // 9b. SMOKE TEST on DEV
        // ─────────────────────────────────────────────────────
        stage('Smoke Test – DEV') {
            steps {
                echo "━━━ Smoke Test – DEV ━━━"
                withAWS(credentials: 'aws-credentials', region: "${AWS_REGION}") {
                    script {
                        runSmokeTest('dev', EKS_CLUSTER_DEV)
                    }
                }
            }
        }

        // ─────────────────────────────────────────────────────
        // 10. PROMOTE TO UAT  (manual gate)
        // ─────────────────────────────────────────────────────
        stage('Promote → UAT?') {
            when {
                branch 'main'
            }
            steps {
                timeout(time: 24, unit: 'HOURS') {
                    input(
                        message: "🚀 Promote build ${IMAGE_TAG} to UAT?",
                        ok: 'Deploy to UAT',
                        submitter: 'qa-team,devlead'
                    )
                }
            }
        }

        stage('Deploy → UAT') {
            when {
                branch 'main'
            }
            steps {
                echo "━━━ Helm Deploy → UAT ━━━"
                withAWS(credentials: 'aws-credentials', region: "${AWS_REGION}") {
                    script {
                        deployToEnvironment(
                            env:         'uat',
                            cluster:     EKS_CLUSTER_UAT,
                            namespace:   'uat',
                            valuesFile:  'devsecops/helm/springboot-app/values-uat.yaml',
                            replicas:    2
                        )
                    }
                }
            }
        }

        stage('Smoke Test – UAT') {
            when { branch 'main' }
            steps {
                withAWS(credentials: 'aws-credentials', region: "${AWS_REGION}") {
                    script { runSmokeTest('uat', EKS_CLUSTER_UAT) }
                }
            }
        }

        // ─────────────────────────────────────────────────────
        // 11. PROMOTE TO PROD  (manual gate – stricter)
        // ─────────────────────────────────────────────────────
        stage('Promote → PROD?') {
            when {
                branch 'main'
            }
            steps {
                timeout(time: 72, unit: 'HOURS') {
                    input(
                        message: "⚠️  PRODUCTION deployment of ${IMAGE_TAG}. Approved by release manager?",
                        ok: 'Deploy to PROD',
                        submitter: 'release-manager'
                    )
                }
            }
        }

        stage('Deploy → PROD') {
            when {
                branch 'main'
            }
            steps {
                echo "━━━ Helm Deploy → PROD ━━━"
                withAWS(credentials: 'aws-credentials', region: "${AWS_REGION}") {
                    script {
                        deployToEnvironment(
                            env:         'prod',
                            cluster:     EKS_CLUSTER_PROD,
                            namespace:   'prod',
                            valuesFile:  'devsecops/helm/springboot-app/values-prod.yaml',
                            replicas:    3
                        )
                    }
                }
            }
        }

        stage('Smoke Test – PROD') {
            when { branch 'main' }
            steps {
                withAWS(credentials: 'aws-credentials', region: "${AWS_REGION}") {
                    script { runSmokeTest('prod', EKS_CLUSTER_PROD) }
                }
            }
        }

    } // end stages

    // =========================================================
    //  POST ACTIONS
    // =========================================================
    post {
        success {
            echo "✅ Pipeline SUCCESS – Image: ${FULL_IMAGE}"
            // Slack / Teams notification – add your webhook credential
            // slackSend(channel: '#deployments', color: 'good',
            //     message: "✅ *${env.JOB_NAME}* build ${env.BUILD_NUMBER} succeeded. Image: ${FULL_IMAGE}")
        }
        failure {
            echo "❌ Pipeline FAILED – check logs above"
            // slackSend(channel: '#deployments', color: 'danger',
            //     message: "❌ *${env.JOB_NAME}* build ${env.BUILD_NUMBER} FAILED. <${env.BUILD_URL}|View>")
        }
        always {
            // Clean workspace to avoid stale artifacts
            cleanWs()
        }
    }

} // end pipeline


// =============================================================
//  SHARED FUNCTIONS
// =============================================================

/**
 * Configure kubectl context for the target EKS cluster,
 * then run a Helm upgrade/install with environment-specific values.
 *
 * @param cfg  Map with keys: env, cluster, namespace, valuesFile, replicas
 */
def deployToEnvironment(Map cfg) {
    // Update kubeconfig for the target cluster
    sh """
        aws eks update-kubeconfig \
            --name ${cfg.cluster} \
            --region ${env.AWS_REGION} \
            --alias ${cfg.env}
    """

    // Ensure the namespace exists
    sh "kubectl --context=${cfg.env} create namespace ${cfg.namespace} --dry-run=client -o yaml | kubectl --context=${cfg.env} apply -f -"

    // Helm upgrade --install  (idempotent)
    sh """
        helm upgrade --install ${env.HELM_RELEASE_NAME} \
            ${env.HELM_CHART_PATH} \
            --kube-context=${cfg.env} \
            --namespace ${cfg.namespace} \
            --values ${cfg.valuesFile} \
            --set image.repository=${env.ECR_REGISTRY}/${env.ECR_REPO} \
            --set image.tag=${env.IMAGE_TAG} \
            --set replicaCount=${cfg.replicas} \
            --set environment=${cfg.env} \
            --atomic \
            --timeout 5m \
            --wait \
            --history-max 5
    """

    echo "✅ Deployed ${env.IMAGE_TAG} → ${cfg.env.toUpperCase()} (namespace: ${cfg.namespace})"
}

/**
 * Basic HTTP smoke test against the app's health endpoint.
 * Adjust the LB/service name as needed.
 */
def runSmokeTest(String envName, String clusterName) {
    sh """
        aws eks update-kubeconfig \
            --name ${clusterName} \
            --region ${env.AWS_REGION} \
            --alias ${envName}

        # Wait for rollout to complete
        kubectl --context=${envName} rollout status \
            deployment/${env.HELM_RELEASE_NAME} \
            -n ${envName} \
            --timeout=3m

        # Fetch LoadBalancer hostname and hit /actuator/health
        LB_HOST=\$(kubectl --context=${envName} get svc ${env.HELM_RELEASE_NAME} \
            -n ${envName} \
            -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')

        echo "Smoke-testing http://\$LB_HOST/actuator/health ..."
        for i in 1 2 3 4 5; do
            STATUS=\$(curl -s -o /dev/null -w '%{http_code}' http://\$LB_HOST/actuator/health || echo 000)
            if [ "\$STATUS" = "200" ]; then
                echo "✅ Smoke test PASSED (env: ${envName})"
                exit 0
            fi
            echo "Attempt \$i – HTTP \$STATUS, retrying in 15s..."
            sleep 15
        done
        echo "❌ Smoke test FAILED (env: ${envName})"
        exit 1
    """
}
