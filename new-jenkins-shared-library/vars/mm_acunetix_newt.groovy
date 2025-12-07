import java.text.SimpleDateFormat
import groovy.json.JsonSlurper

def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def AGENT = config.AGENT
    def ENVIRONMENT = config.ENVIRONMENT
    def job_name = env.JOB_NAME
    def build_number = env.BUILD_NUMBER.toInteger()
    def scanname = "${job_name}jenkins_build${build_number}"
    def MyAXURL = "https://acunetix.mahindra.com/api/v1" 
    def MyTargetDESC = "Created Target using ${job_name}jenkins_build${build_number}"
    def FullScanProfileID = "11111111-1111-1111-1111-111111111111"
    def MyTargetID = null
    def MyScanID = null
    def lastScanDate = null
    def ProdReady = config.ProdReady
    def PROJECT_NAME = config.PROJECT_NAME
    def pipelineStage = ""

    try {
        node("$AGENT") {
            // Initialize build result as SUCCESS
            currentBuild.result = 'SUCCESS'
            
            stage('Checkout source code') {
                pipelineStage = "${STAGE_NAME}"
                try {
                    checkout scm
                    COMMIT_HASH = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                    echo "Commit Hash: ${COMMIT_HASH}"
                    echo "Checkout Status: SUCCESS"
                } catch (Exception e) {
                    echo "Checkout Status: FAILURE - ${e.getMessage()}"
                    currentBuild.result = 'FAILURE'
                    throw e
                }
            }
            
            stage('Acunetix Check and Create') {
                pipelineStage = "${STAGE_NAME}"
                script {
                    try {
                        withCredentials([string(credentialsId: 'ACUNETIX_API_KEY', variable: 'MyAPIKEY')]) {
                            def MyTargetURL
                            def yamlFilePath = "${WORKSPACE}/helm-chart/environment/${ENVIRONMENT}-values.yaml"
                            
                            // Check if YAML file exists
                            if (!fileExists(yamlFilePath)) {
                                throw new Exception("YAML file not found at path: ${yamlFilePath}")
                            }
                            
                            try {
                                def data = readYaml file: yamlFilePath
                                
                                // Try to get URL from different possible locations
                                if (data.virtualservice?.host) {
                                    MyTargetURL = data.virtualservice.host
                                } else if (data.ingress?.host) {
                                    MyTargetURL = data.ingress.host
                                } else if (data.host) {
                                    MyTargetURL = data.host
                                } else {
                                    throw new Exception("Could not find host configuration in YAML file. Checked virtualservice.host, ingress.host, and host fields.")
                                }
                                
                                echo "Target URL found: ${MyTargetURL}"
                                
                            } catch (Exception e) {
                                throw new Exception("Error reading YAML file: ${e.getMessage()}")
                            }

                            // Validate MyTargetURL
                            if (!MyTargetURL || MyTargetURL.trim().isEmpty()) {
                                throw new Exception("Target URL is empty or null")
                            }

                            // Check if the target exists
                            def checkIfExistsCommand = "curl -sS -k -X GET \"${MyAXURL}/targets?l=100&q=text_search:*${MyTargetURL}\" -H \"Content-Type: application/json\" -H \"X-Auth: ${MyAPIKEY}\""
                            def checkIfExists = sh(script: checkIfExistsCommand, returnStdout: true).trim()
                            def targetExists = false

                            if (checkIfExists && checkIfExists.contains("target_id")) {
                                try {
                                    def jsonResponse = readJSON text: checkIfExists
                                    if (jsonResponse.targets) {
                                        def target = jsonResponse.targets.find { it.address == MyTargetURL }
                                        if (target) {
                                            MyTargetID = target.target_id
                                            lastScanDate = target.last_scan_date
                                            targetExists = true
                                            echo "Existing target found with ID: ${MyTargetID}"
                                        }
                                    }
                                } catch (Exception e) {
                                    echo "Error parsing target check response: ${e.getMessage()}"
                                }
                            }

                            // If the target URL doesn't exist, create a new target
                            if (!targetExists) {
                                echo "Target not found. Creating new target..."
                                def createTargetCommand = "curl -sS -k -X POST ${MyAXURL}/targets -H \"Content-Type: application/json\" -H \"X-Auth: ${MyAPIKEY}\" --data '{\"address\":\"${MyTargetURL}\",\"description\":\"${MyTargetDESC}\",\"type\":\"default\",\"criticality\":10}'"
                                def createTargetResponse = sh(script: createTargetCommand, returnStdout: true).trim()
                                
                                if (createTargetResponse && createTargetResponse.contains("target_id")) {
                                    MyTargetID = sh(script: "echo '${createTargetResponse}' | sed -n 's/.*\"target_id\": *\"\\([^\"]*\\)\".*/\\1/p'", returnStdout: true).trim()
                                    echo "New target created with ID: ${MyTargetID}"
                                } else {
                                    throw new Exception("Failed to create target. Response: ${createTargetResponse}")
                                }
                            }

                            if (!MyTargetID || MyTargetID.trim().isEmpty()) {
                                throw new Exception("Failed to obtain target ID")
                            }

                            echo "MyTargetID is: ${MyTargetID}"
                            echo "Acunetix Check and Create Status: SUCCESS"
                        }
                    } catch (Exception e) {
                        echo "Acunetix Check and Create Status: FAILURE - ${e.getMessage()}"
                        currentBuild.result = 'FAILURE'
                        throw e
                    }
                }
            }

            stage('Acunetix WAS scanning') {
                pipelineStage = "${STAGE_NAME}"
                script {
                    try {
                        withCredentials([string(credentialsId: 'ACUNETIX_API_KEY', variable: 'MyAPIKEY')]) {
                            def scanProfileID = FullScanProfileID
                            def incrementalScan = false

                            if (lastScanDate && lastScanDate != "null") {
                                try {
                                    def dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
                                    def lastScan = dateFormat.parse(lastScanDate)
                                    def currentDate = new Date()
                                    def diff = currentDate.time - lastScan.time
                                    def daysDiff = diff / (1000 * 60 * 60 * 24)
                                    if (daysDiff <= 40) {
                                        incrementalScan = true
                                        echo "Using incremental scan (last scan was ${daysDiff} days ago)"
                                    } else {
                                        echo "Using full scan (last scan was ${daysDiff} days ago)"
                                    }
                                } catch (Exception e) {
                                    echo "Error parsing last scan date: ${e.message}"
                                }
                            }

                            // Create a new scan
                            echo "Creating a new scan with profile ID: ${scanProfileID}"
                            def createScanCommand = "curl -sS -k -X POST ${MyAXURL}/scans -H \"Content-Type: application/json\" -H \"X-Auth: ${MyAPIKEY}\" --data '{\"target_id\":\"${MyTargetID}\",\"profile_id\":\"${scanProfileID}\",\"incremental\":${incrementalScan},\"schedule\":{\"disable\":false}}'"
                            def createScanResponse = sh(script: createScanCommand, returnStdout: true).trim()
                            
                            if (createScanResponse && createScanResponse.contains("scan_id")) {
                                MyScanID = sh(script: "echo '${createScanResponse}' | grep -Po '\"scan_id\": *\\K\"[^\"]*\"' | tr -d '\"'", returnStdout: true).trim()
                                echo "MyScanID is: ${MyScanID}"
                            } else {
                                throw new Exception("Failed to create scan. Response: ${createScanResponse}")
                            }

                            if (!MyScanID || MyScanID.trim().isEmpty()) {
                                throw new Exception("Failed to obtain scan ID")
                            }
                            
                            echo "Acunetix WAS scanning Status: SUCCESS"
                        }
                    } catch (Exception e) {
                        echo "Acunetix WAS scanning Status: FAILURE - ${e.getMessage()}"
                        currentBuild.result = 'FAILURE'
                        throw e
                    }
                }
            }
            
            stage('Checking Scan Status') {
                pipelineStage = "${STAGE_NAME}"
                script {
                    try {
                        withCredentials([string(credentialsId: 'ACUNETIX_API_KEY', variable: 'MyAPIKEY')]) {
                            sleep 15 // Add a 15-second sleep before checking the scan status
                            def isCompleted = false
                            def maxAttempts = 240 // Maximum attempts (2 hours if checking every 30 seconds)
                            def attempts = 0
                            
                            while (!isCompleted && attempts < maxAttempts) {
                                attempts++
                                
                                try {
                                    // Get the result_id
                                    def resultIdResponse = sh(script: "curl -sS -k -X GET \"${MyAXURL}/scans/${MyScanID}/results\" -H \"Accept: application/json\" -H \"X-Auth: ${MyAPIKEY}\"", returnStdout: true).trim()
                                    def resultId = sh(script: "echo '${resultIdResponse}' | grep -Po '\"result_id\": *\\K\"[^\"]*\"' | tr -d '\"'", returnStdout: true).trim()

                                    if (resultId && !resultId.isEmpty()) {
                                        // Get the scan statistics
                                        def statisticsResponse = sh(script: "curl -sS -k -X GET \"${MyAXURL}/scans/${MyScanID}/results/${resultId}/statistics\" -H \"Accept: application/json\" -H \"X-Auth: ${MyAPIKEY}\"", returnStdout: true).trim()
                                        
                                        // Extract progress and status
                                        def progress = sh(script: "echo '${statisticsResponse}' | grep -Po '\"progress\": *\\K[0-9]+'", returnStdout: true).trim()
                                        def status = sh(script: "echo '${statisticsResponse}' | grep -Po '\"status\": *\\K\"[^\"]*\"' | tr -d '\"' | head -n 1", returnStdout: true).trim()

                                        echo "Scan Status: ${status} - Progress: ${progress}% (Attempt ${attempts}/${maxAttempts})"

                                        if (status == "finished") {
                                            echo "Scan Status: Completed"
                                            isCompleted = true
                                        } else if (status == "failed" || status == "aborted") {
                                            throw new Exception("Scan failed with status: ${status}")
                                        } else {
                                            echo "Scan is still in progress. Waiting 30 seconds before next check."
                                            sleep 30
                                        }
                                    } else {
                                        echo "Failed to retrieve result_id. Waiting 30 seconds before next check. (Attempt ${attempts}/${maxAttempts})"
                                        sleep 30
                                    }
                                } catch (Exception e) {
                                    echo "Error checking scan status: ${e.getMessage()}. Waiting 30 seconds before retry."
                                    sleep 30
                                }
                            }
                            
                            if (!isCompleted) {
                                throw new Exception("Scan did not complete within the timeout period (${maxAttempts} attempts)")
                            }
                            
                            echo "Checking Scan Status: SUCCESS"
                        }
                    } catch (Exception e) {
                        echo "Checking Scan Status: FAILURE - ${e.getMessage()}"
                        currentBuild.result = 'FAILURE'
                        throw e
                    }
                }
            }
            
            stage('Creating Report') {
                pipelineStage = "${STAGE_NAME}"
                script {
                    try {
                        withCredentials([string(credentialsId: 'ACUNETIX_API_KEY', variable: 'MyAPIKEY')]) {
                            // Obtain the Scan Result ID
                            def scanResultResponse = sh(script: "curl -sS -k -X GET \"${MyAXURL}/scans/${MyScanID}/results\" -H \"Accept: application/json\" -H \"X-Auth: ${MyAPIKEY}\"", returnStdout: true).trim()
                            def MyScanResultID = sh(script: "echo '${scanResultResponse}' | grep -Po '\"result_id\": *\\K\"[^\"]*\"' | tr -d '\"'", returnStdout: true).trim()

                            if (!MyScanResultID || MyScanResultID.trim().isEmpty()) {
                                throw new Exception("Failed to obtain scan result ID")
                            }

                            // Generate the PDF report
                            def reportCommand = "curl -sS -k -X POST ${MyAXURL}/reports -H \"Content-Type: application/json\" -H \"X-Auth: ${MyAPIKEY}\" --data '{\"template_id\":\"11111111-1111-1111-1111-111111111115\",\"source\":{\"list_type\":\"scans\",\"id_list\":[\"${MyScanID}\"]}}'"
                            def reportResponse = sh(script: reportCommand, returnStdout: true).trim()
                            def reportID = sh(script: "echo '${reportResponse}' | grep -Po '\"report_id\": *\\K\"[^\"]*\"' | tr -d '\"'", returnStdout: true).trim()

                            if (!reportID || reportID.trim().isEmpty()) {
                                throw new Exception("Failed to generate report. Response: ${reportResponse}")
                            }

                            echo "Report ID: ${reportID}"
                            sleep 30

                            // Wait for report generation to complete
                            def reportReady = false
                            def reportAttempts = 0
                            def maxReportAttempts = 20
                            
                            while (!reportReady && reportAttempts < maxReportAttempts) {
                                reportAttempts++
                                
                                def reportDetailsCommand = "curl -sS -k -X GET \"${MyAXURL}/reports/${reportID}\" -H \"Accept: application/json\" -H \"X-Auth: ${MyAPIKEY}\""
                                def reportDetailsResponse = sh(script: reportDetailsCommand, returnStdout: true).trim()

                                // Extract the PDF download URL using grep and sed
                                def pdfDownloadUrl = sh(script: "echo '${reportDetailsResponse}' | grep -o '\"/api/v1/reports/download/[^\"]*\\.pdf\"' | sed 's/\"//g' | head -n 1", returnStdout: true).trim()
                                
                                if (pdfDownloadUrl && !pdfDownloadUrl.isEmpty()) {
                                    echo "Full Download URL: ${pdfDownloadUrl}"
                                    reportReady = true
            
                                    // Extract the descriptor from the URL
                                    def descriptor = sh(script: "echo '${pdfDownloadUrl}' | sed 's/.*\\///'", returnStdout: true).trim() 
                                    echo "Extracted descriptor: ${descriptor}"

                                    // Download the PDF report
                                    def downloadReportCommand = "curl -sS -k -X GET \"${MyAXURL}/reports/download/${descriptor}\" -H \"Accept: application/json\" -H \"X-Auth: ${MyAPIKEY}\" --output ${WORKSPACE}/acunetix_report.pdf"
                                    def downloadReportResponse = sh(script: downloadReportCommand, returnStatus: true)
            
                                    if (downloadReportResponse == 0) {
                                        echo "Report downloaded to ${WORKSPACE}/acunetix_report.pdf"
                                        archiveArtifacts 'acunetix_report.pdf'

                                        if (ProdReady != null && ProdReady != "") {
                                            // Use Jenkins instance URL from global configuration
                                            final String JENKINS_BASE_URL = 'https://jenkins1.m-devsecops.com/'
                                            
                                            try {
                                                // Create job path without URL encoding for better readability
                                                def jobPath = env.JOB_NAME.replaceAll('/', '/job/')
                                                
                                                // Construct URL using Jenkins permalink structure
                                                env.ARTIFACT_URL = "${JENKINS_BASE_URL}job/${jobPath}/lastSuccessfulBuild/artifact/acunetix_report.pdf"
                                                
                                                echo "Generated Artifact URL: ${env.ARTIFACT_URL}"

                                                // Execute API call with proper error handling
                                                def apiResponse = sh(script: """
                                                    curl -X POST \\
                                                        -H "Content-Type: application/json" \\
                                                        -d '{"id":"test-vmalert/test-vmalert/sqltest6/main/65097a8c5","url":"${env.ARTIFACT_URL}"}' \\
                                                        --fail \\
                                                        --retry 3 \\
                                                        --retry-delay 5 \\
                                                        --write-out "%{http_code}" \\
                                                        --output /dev/null \\
                                                        'https://mpaasdev.m-devsecops.com/api/v1/organizations/mahindra-dev/projects/test-vmalert/environment/DEV/jenkins/ccf/upload'
                                                """, returnStdout: true).trim()
                                                
                                                if (apiResponse == "200") {
                                                    echo "Successfully uploaded artifact URL to API"
                                                } else {
                                                    echo "API call returned status: ${apiResponse}"
                                                }
                                                
                                            } catch(Exception e) {
                                                echo "Warning: Failed to upload artifact URL to API: ${e.getMessage()}"
                                                // Don't fail the build for API upload issues
                                            }
                                        }
                                    } else {
                                        throw new Exception("Failed to download the report")
                                    }
                                } else {
                                    echo "Report not ready yet. Waiting 30 seconds... (Attempt ${reportAttempts}/${maxReportAttempts})"
                                    sleep 30
                                }
                            }
                            
                            if (!reportReady) {
                                throw new Exception("Report generation timed out after ${maxReportAttempts} attempts")
                            }
                            
                            echo "Creating Report Status: SUCCESS"
                        }
                    } catch (Exception e) {
                        echo "Creating Report Status: FAILURE - ${e.getMessage()}"
                        currentBuild.result = 'FAILURE'
                        throw e
                    }
                }
            }
            
            // If we reach here, all stages completed successfully
            // Explicitly set the build result to SUCCESS
            currentBuild.result = 'SUCCESS'
            echo "Pipeline Status: SUCCESS"
            echo "All stages completed successfully"
        }
    } catch (Exception err) {
        echo "Pipeline Status: FAILURE"
        echo "Failed at stage: ${pipelineStage}"
        echo "Error details: ${err.getMessage()}"
        println(err)
        currentBuild.result = 'FAILURE'
        throw err
    }
}