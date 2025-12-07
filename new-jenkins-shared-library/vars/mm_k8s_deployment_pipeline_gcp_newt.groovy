import java.text.SimpleDateFormat
import hudson.tasks.test.AbstractTestResultAction
import hudson.model.Actionable
import hudson.model.Result

def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def PROJECT                 = config.PROJECT
    def ENVIRONMENT             = config.ENVIRONMENT
    def DEPLOYMENT_NAME         = config.DEPLOYMENT_NAME
    def NAMESPACE               = ENVIRONMENT + "-" + DEPLOYMENT_NAME

    def CLUSTER_NAME            = config.CLUSTER_NAME
    def REGION                  = config.REGION
    def COMMIT_HASH             = ""
    def DEPLOYMENT_TYPE         = config.DEPLOYMENT_TYPE
    def RESTRICT_TO_TAGS        = config.RESTRICT_TO_TAGS ?: false
    def TAG_NAME                = ""
    def HELM_TAG_NAME           = ""
    def RESTRICTED              = false
    def REGISTRY_NAME           = config.REGISTRY_NAME
    def IMAGE_NAME              = config.IMAGE_NAME
    def CANARY                  = config.CANARY ?: false
    def gcloud                  = new org.mahindra.Gcloud()
    def AGENT                   = config.AGENT ?: PROJECT
    def git                     = new org.mahindra.Git()

    try {
        node(AGENT) {
            currentBuild.result = 'SUCCESS'

            stage("Deploy to ${ENVIRONMENT}") {
                withCredentials([
                    string(credentialsId: "login", variable: 'new')
                ]) {
                    sh '''echo "${new}" | sudo -S chmod 777 /var/run/docker.sock'''
                    sh 'gcloud auth activate-service-account --key-file=/home/agent/mpaas-prod-all-access.json --project=modernization-dev-832770'
                    sh 'gcloud auth configure-docker'

                    docker.image("asia.gcr.io/modernization-dev-832770/google/cloud-sdk:alpine").inside("-v /home/agent/mpaas-prod-all-access.json:/home/agent/mpaas-prod-all-access.json --net=host --user root --privileged") {
                        // Checkout source code
                        pipelineStage = "${STAGE_NAME}"
                        git.gitCheckout(5)
                        COMMIT_HASH = sh(returnStdout: true, script: 'git rev-parse HEAD')
                        HELM_TAG_NAME = "${COMMIT_HASH.substring(0,9)}"
                        echo "Source code checked out successfully"
                        
                        // Check restrictions (Tags only)
                        if (RESTRICT_TO_TAGS) {
                            TAG_NAME = sh(returnStdout: true, script: 'git tag --points-at HEAD')
                            if ("${TAG_NAME}" == "") {
                                RESTRICTED = true
                                error "RESTRICT_TO_TAGS was enabled, but no tags were found"
                            }
                            echo "Found tag ${TAG_NAME}"
                        }

                        if (DEPLOYMENT_TYPE == null || DEPLOYMENT_TYPE.equals("")) {
                            error "DEPLOYMENT_TYPE should be 'automatic' or 'manual', got ${DEPLOYMENT_TYPE} instead"
                        }
                        
                        // SA impersonation
                        echo "Performing SA impersonation..."
                        sh """
                            gcloud auth activate-service-account --key-file=/home/agent/mpaas-prod-all-access.json
                            gcloud iam service-accounts add-iam-policy-binding \
                                --role roles/iam.workloadIdentityUser \
                                --member "serviceAccount:${PROJECT}.svc.id.goog[${NAMESPACE}/default-sa]" \
                                gke-workload@${PROJECT}.iam.gserviceaccount.com --project=${PROJECT}
                        """
                    }

                    // Optional: Tag-based image push (if RESTRICT_TO_TAGS is enabled)
                    if (RESTRICT_TO_TAGS && "${TAG_NAME}" != "") {
                        echo "Building and pushing tagged image..."
                        withCredentials([
                            usernamePassword(credentialsId: 'docker-credentials', usernameVariable: 'DOCKER_USERNAME', passwordVariable: 'DOCKER_PASSWORD')
                        ]) {
                            docker.image("docker:dind").inside("--user root --privileged -v /var/run/docker.sock:/var/run/docker.sock") {
                                sh """
                                    docker login ${REGISTRY_NAME}.azurecr.io --username $DOCKER_USERNAME --password $DOCKER_PASSWORD
                                    docker tag ${REGISTRY_NAME}.azurecr.io/$IMAGE_NAME:${COMMIT_HASH.substring(0,9)} ${REGISTRY_NAME}.azurecr.io/$IMAGE_NAME:${TAG_NAME}
                                    docker push ${REGISTRY_NAME}.azurecr.io/$IMAGE_NAME:${TAG_NAME}
                                """
                                HELM_TAG_NAME = "${TAG_NAME}".replaceAll("\\s","")
                                echo "Tagged image pushed successfully"
                            }
                        }
                    }

                    // Deployment (Helm-based)
                    docker.image("asia.gcr.io/modernization-dev-832770/bootlabstech/deployment-tools-gcp:gcloud-sdk-502.0.0_helm-3.16.3_kubectl-1.28.4_gke_auth_plugin_zscalarcaroot-alpine").inside("-v /home/agent/mpaas-prod-all-access.json:/home/agent/mpaas-prod-all-access.json --net=host --user root --privileged") {
                        // Update kubeconfig
                        echo "Updating kubeconfig..."
                        sh """
                            gcloud auth activate-service-account --key-file=/home/agent/mpaas-prod-all-access.json
                            gcloud container clusters get-credentials ${CLUSTER_NAME} --region ${REGION} --project ${PROJECT}
                        """

                        if (CANARY) {
                            // Canary deployment logic
                            echo "Canary deployment enabled - requesting deployment parameters..."
                            def weight = input(id: 'weight', message: "Select Maximum Weight:", parameters: [[$class: 'ChoiceParameterDefinition', choices: "10\n20\n30\n40\n50"]])
                            def stepWeight = input(id: 'stepWeight', message: "Select Step Weight:", parameters: [[$class: 'ChoiceParameterDefinition', choices: "10\n20\n30\n40\n50"]])
                            def interval = input(id: 'interval', message: "Select interval:", parameters: [[$class: 'ChoiceParameterDefinition', choices: "1m\n5m\n15m\n30m\n60m"]])

                            echo "Deploying with Canary settings: maxWeight=${weight}, stepWeight=${stepWeight}, interval=${interval}"
                            sh """
                                helm dependency update helm-chart
                                helm upgrade --install \
                                    --values ${WORKSPACE}/helm-chart/environment/${ENVIRONMENT}-values.yaml \
                                    --set-string image.tag="${HELM_TAG_NAME}" \
                                    --set canary.enabled=true \
                                    --set canary.analysis.maxWeight=${weight} \
                                    --set canary.analysis.stepWeight=${stepWeight} \
                                    --set canary.analysis.interval=${interval} \
                                    -n ${NAMESPACE} --create-namespace \
                                    $DEPLOYMENT_NAME helm-chart
                            """
                            echo "Canary deployment completed successfully"
                        } else {
                            if (DEPLOYMENT_TYPE == "automatic") {
                                echo "Performing automatic deployment..."
                                sh """
                                    helm dependency update helm-chart
                                    helm upgrade --install \
                                        --values ${WORKSPACE}/helm-chart/environment/${ENVIRONMENT}-values.yaml \
                                        --set-string image.tag="${HELM_TAG_NAME}" \
                                        -n ${NAMESPACE} --create-namespace \
                                        $DEPLOYMENT_NAME helm-chart
                                """
                                echo "Automatic deployment completed successfully"
                            } else if (DEPLOYMENT_TYPE == "manual") {
                                echo "Manual deployment - requesting user approval..."
                                def userInput = input(
                                    id: 'userInput', 
                                    message: "Deploy to ${ENVIRONMENT}?", 
                                    parameters: [[$class: 'ChoiceParameterDefinition', choices: "Yes\nNo"]]
                                )
                                if ("${userInput}" == "Yes") {
                                    echo "User approved - performing deployment..."
                                    sh """
                                        helm dependency update helm-chart
                                        helm upgrade --install \
                                            --values ${WORKSPACE}/helm-chart/environment/${ENVIRONMENT}-values.yaml \
                                            --set-string image.tag="${HELM_TAG_NAME}" \
                                            -n ${NAMESPACE} --create-namespace \
                                            $DEPLOYMENT_NAME helm-chart
                                    """
                                    echo "Manual deployment completed successfully"
                                } else {
                                    echo "User declined deployment"
                                }
                            }
                        }
                    }
                }
            }
        }
    } catch (Exception err) {
        node(AGENT) {
            if (RESTRICTED && RESTRICT_TO_TAGS) {
                currentBuild.result = 'SUCCESS'
            } else {
                currentBuild.result = 'FAILURE'
                error "Deployment failed: ${err}"
            }
        }
    }
}