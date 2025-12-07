import java.text.SimpleDateFormat
import groovy.json.JsonSlurper

def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def ENVIRONMENT = config.ENVIRONMENT
    def job_name = env.JOB_NAME.trim()
    def build_number = env.BUILD_NUMBER.toInteger()
    def scanname = "${job_name}jenkins_build${build_number}"
    def MyAXURL = "https://acunetix.mahindra.com/api/v1"
    def MyTargetDESC = "Created Target using ${job_name}jenkins_build${build_number}"
    def FullScanProfileID = "11111111-1111-1111-1111-111111111111"
    def MyTargetID = null
    def MyScanID = null
    def lastScanDate = null
    def MyScanResultID = null

    try {
        podTemplate(
            nodeSelector: 'cloud.google.com/gke-nodepool=spot-node-pool-1',
            containers: [
                containerTemplate(
                    name: 'jnlp',
                    image: 'asia.gcr.io/common-infra-services/jenkins-agent-updated:latest'
                )
            ],
            volumes: [
                hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock'),
                persistentVolumeClaim(claimName: 'jenkinsagentpvc', mountPath: '/home/jenkins/agent', readOnly: false)
            ]
        ) {
            node(POD_LABEL) {
                currentBuild.result = 'SUCCESS'

                stage('DAST Scan: Acunetix') {
                    script {
                        pipelineStage = "${STAGE_NAME}"
                        
                        // ==================== CHECKOUT SOURCE CODE ====================
                        echo "=== CHECKOUT SOURCE CODE ==="
                        checkout scm
                        def COMMIT_HASH = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                        echo "Checked out commit hash: ${COMMIT_HASH}"
                        
                        withCredentials([string(credentialsId: 'ACUNETIX_API_KEY', variable: 'MyAPIKEY')]) {
                            def data = readYaml file: "${WORKSPACE}/helm-chart/environment/${ENVIRONMENT}-values.yaml"
                            def MyTargetURL

                            try {
                                MyTargetURL = data.virtualservice.host ?: data.ingress.host
                            } catch (err) {
                                MyTargetURL = data.ingress.host
                            }

                            echo "Target URL: ${MyTargetURL}"
                            
                            // ==================== ACUNETIX CHECK AND CREATE TARGET ====================
                            echo "=== ACUNETIX CHECK AND CREATE TARGET ==="
                            def checkIfExistsCommand = "curl -sS -k -X GET \"$MyAXURL/targets?l=100&q=text_search:*$MyTargetURL\" -H \"Content-Type: application/json\" -H \"X-Auth: $MyAPIKEY\""
                            def checkIfExists = sh(script: checkIfExistsCommand, returnStdout: true).trim()
                            def targetExists = false

                            if (checkIfExists.contains("target_id")) {
                                def jsonResponse = readJSON text: checkIfExists
                                def target = jsonResponse.targets.find { it.address == MyTargetURL }
                                if (target) {
                                    MyTargetID = target.target_id
                                    lastScanDate = target.last_scan_date
                                    targetExists = true
                                    echo "✓ Existing target found: ${MyTargetID}"
                                }
                            }

                            if (!targetExists) {
                                echo "Target not found. Creating new target..."
                                def createTargetCommand = "curl -sS -k -X POST $MyAXURL/targets -H \"Content-Type: application/json\" -H \"X-Auth: $MyAPIKEY\" --data '{\"address\":\"$MyTargetURL\",\"description\":\"$MyTargetDESC\",\"type\":\"default\",\"criticality\":10}'"
                                def createTargetResponse = sh(script: createTargetCommand, returnStdout: true).trim()
                                MyTargetID = sh(script: "echo '${createTargetResponse}' | sed -n 's/.*\"target_id\": *\"\\([^\"]*\\)\".*/\\1/p'", returnStdout: true).trim()
                                echo "✓ New target created: ${MyTargetID}"
                            }

                            // ==================== ACUNETIX WAS SCANNING ====================
                            echo "=== STARTING ACUNETIX SCAN ==="
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
                                        echo "Using incremental scan (last scan was ${daysDiff.toInteger()} days ago)"
                                    }
                                } catch (Exception e) {
                                    echo "Error parsing last scan date: ${e.message}"
                                }
                            }

                            echo "Creating a new scan with profile ID: ${scanProfileID}"
                            def createScanCommand = "curl -sS -k -X POST $MyAXURL/scans -H \"Content-Type: application/json\" -H \"X-Auth: $MyAPIKEY\" --data '{\"target_id\":\"$MyTargetID\",\"profile_id\":\"$scanProfileID\",\"incremental\":${incrementalScan},\"schedule\":{\"disable\":false}}'"
                            def createScanResponse = sh(script: createScanCommand, returnStdout: true).trim()
                            MyScanID = sh(script: "echo '${createScanResponse}' | grep -Po '\"scan_id\": *\\K\"[^\"]*\"' | tr -d '\"'", returnStdout: true).trim()

                            echo "✓ Scan started: ${MyScanID}"
                            
                            // ==================== MONITORING SCAN PROGRESS ====================
                            echo "=== MONITORING SCAN PROGRESS ==="
                            sleep 30
                            def isCompleted = false
                            def maxWaitTime = 3600 // 60 minutes maximum
                            def waitTime = 0
                            
                            while (!isCompleted && waitTime < maxWaitTime) {
                                try {
                                    def resultIdResponse = sh(script: "curl -sS -k -X GET \"$MyAXURL/scans/${MyScanID}/results\" -H \"Accept: application/json\" -H \"X-Auth: $MyAPIKEY\"", returnStdout: true).trim()
                                    def resultId = sh(script: "echo '${resultIdResponse}' | grep -Po '\"result_id\": *\\K\"[^\"]*\"' | tr -d '\"'", returnStdout: true).trim()
        
                                    if (resultId) {
                                        // Store result ID for later use in vulnerability report
                                        MyScanResultID = resultId
                                        
                                        def statisticsResponse = sh(script: "curl -sS -k -X GET \"$MyAXURL/scans/${MyScanID}/results/${resultId}/statistics\" -H \"Accept: application/json\" -H \"X-Auth: $MyAPIKEY\"", returnStdout: true).trim()
                                        
                                        def progressCheck = sh(
                                            script: "echo '${statisticsResponse}' | grep -Po '\"progress\": *\\K[0-9]+'",
                                            returnStatus: true
                                        )
                                        
                                        def progress = "N/A"
                                        if (progressCheck == 0) {
                                            progress = sh(
                                                script: "echo '${statisticsResponse}' | grep -Po '\"progress\": *\\K[0-9]+'",
                                                returnStdout: true
                                            ).trim()
                                        }
                                        
                                        def status = sh(script: "echo '${statisticsResponse}' | grep -Po '\"status\": *\\K\"[^\"]*\"' | tr -d '\"' | head -n 1", returnStdout: true).trim()
        
                                        echo "Scan Status: ${status} - Progress: ${progress}% (Elapsed: ${waitTime/60} minutes)"
        
                                        if (status == "finished" || status == "completed") {
                                            echo "✓ Scan completed successfully"
                                            isCompleted = true
                                        } else {
                                            echo "Scan is still in progress. Waiting 30 seconds before next check."
                                            sleep 30
                                            waitTime += 30
                                        }
                                    } else {
                                        echo "Failed to retrieve result_id. Waiting 30 seconds before next check."
                                        sleep 30
                                        waitTime += 30
                                    }
                                } catch (Exception e) {
                                    echo "Error during status check: ${e.message}"
                                    echo "Retrying in 30 seconds..."
                                    sleep 30
                                    waitTime += 30
                                }
                            }
                            
                            if (!isCompleted) {
                                error("Scan did not complete within ${maxWaitTime/60} minutes. Please check Acunetix dashboard manually.")
                            }

                            // ==================== GENERATING VULNERABILITY REPORT ====================
                            echo "=== GENERATING VULNERABILITY REPORT ==="
                            generateAcunetixVulnerabilityReport(MyAXURL, MyScanID, MyScanResultID)
                            
                            // ==================== CREATING AND DOWNLOADING PDF REPORT ====================
                            echo "=== CREATING AND DOWNLOADING PDF REPORT ==="
                            def scanResultResponse = sh(script: "curl -sS -k -X GET \"$MyAXURL/scans/${MyScanID}/results\" -H \"Accept: application/json\" -H \"X-Auth: $MyAPIKEY\"", returnStdout: true).trim()
                            MyScanResultID = sh(script: "echo '${scanResultResponse}' | grep -Po '\"result_id\": *\\K\"[^\"]*\"' | tr -d '\"'", returnStdout: true).trim()

                            def reportCommand = "curl -sS -k -X POST $MyAXURL/reports -H \"Content-Type: application/json\" -H \"X-Auth: $MyAPIKEY\" --data '{\"template_id\":\"11111111-1111-1111-1111-111111111115\",\"source\":{\"list_type\":\"scans\",\"id_list\":[\"$MyScanID\"]}}'"
                            def reportResponse = sh(script: reportCommand, returnStdout: true).trim()
                            def reportID = sh(script: "echo '${reportResponse}' | grep -Po '\"report_id\": *\\K\"[^\"]*\"' | tr -d '\"'", returnStdout: true).trim()

                            echo "Report ID: ${reportID}"
                            echo "Waiting for report generation..."
                            sleep 30

                            def reportDetailsCommand = "curl -sS -k -X GET \"$MyAXURL/reports/${reportID}\" -H \"Accept: application/json\" -H \"X-Auth: $MyAPIKEY\""
                            def reportDetailsResponse = sh(script: reportDetailsCommand, returnStdout: true).trim()

                            def pdfDownloadUrl = sh(script: "echo '${reportDetailsResponse}' | grep -o '\"/api/v1/reports/download/[^\"]*\\.pdf\"' | sed 's/\"//g' | head -n 1", returnStdout: true).trim()

                            if (pdfDownloadUrl) {
                                echo "Full Download URL: ${pdfDownloadUrl}"
                                def descriptor = sh(script: "echo '${pdfDownloadUrl}' | sed 's/.*\\///'", returnStdout: true).trim()
                                echo "Extracted descriptor: ${descriptor}"

                                def downloadReportCommand = "curl -sS -k -X GET \"$MyAXURL/reports/download/${descriptor}\" -H \"Accept: application/json\" -H \"X-Auth: $MyAPIKEY\" --output ${WORKSPACE}/acunetix_report.pdf"
                                def downloadReportResponse = sh(script: downloadReportCommand, returnStatus: true)

                                if (downloadReportResponse == 0) {
                                    echo "✓ Report downloaded to ${WORKSPACE}/acunetix_report.pdf"
                                    archiveArtifacts 'acunetix_report.pdf'
                                    
                                    // Stash reports including vulnerability count
                                    stash includes: 'acunetix_report.pdf,vulnerability_count_acunetix.txt', name: 'acunetix-reports'
                                    echo "✓ Reports stashed for pipeline use"
                                } else {
                                    error("Failed to download the report")
                                }
                            } else {
                                error("PDF download URL not found in the response")
                            }
                        }
                        
                        // ==================== UPLOADING REPORT TO MPAAS (CONDITIONAL) ====================
                        if (config.CCF_Required?.toLowerCase() == 'yes') {
                            echo "=== UPLOADING REPORT TO MPAAS ==="
                            container('jnlp') {
                                withCredentials([file(credentialsId: 'gcp-service-account-key', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
                                    script {
                                        def jobParts = job_name.split('/')
                                        def projectname = jobParts[0]
                                        def branchName = jobParts.size() > 1 ? jobParts[1] : 'main'
                                        def jenkinsBuildFolder = "${projectname}jenkins_build${BUILD_NUMBER}"
                                        def hierarchicalPath = "${projectname}/${branchName}/${jenkinsBuildFolder}"
                                        def objectPath = "${hierarchicalPath}/acunetix_report.pdf"
                                        echo "Creating folder structure: ${projectname} -> ${branchName} -> ${jenkinsBuildFolder}"
                                        def bucketName = "jenkins-ccf-security-reports"
                                        def projectId = "common-infra-services"
                                        def projectName = config.PROJECT_NAME ?: "your-project-name"
                                        def envName = config.ENVIRONMENT ?: "DEV"

                                        echo "Preparing to upload report to GCS and notify mPaas API..."

                                        try {
                                            sh '''
                                                set -e
                                                apt-get update -y
                                                apt-get install -y curl bash
                                                if ! command -v gsutil &> /dev/null; then
                                                    echo "Installing Google Cloud SDK..."
                                                    curl https://sdk.cloud.google.com | bash
                                                    export CLOUD_SDK_HOME="$HOME/google-cloud-sdk"
                                                    export PATH="$CLOUD_SDK_HOME/bin:$PATH"
                                                    . "$CLOUD_SDK_HOME/path.bash.inc"
                                                else
                                                    export CLOUD_SDK_HOME="$HOME/google-cloud-sdk"
                                                    export PATH="$CLOUD_SDK_HOME/bin:$PATH"
                                                fi

                                                gcloud auth activate-service-account --key-file=${GOOGLE_APPLICATION_CREDENTIALS}
                                                gcloud config set project ''' + projectId + '''

                                                HIERARCHICAL_PATH="''' + hierarchicalPath + '''"
                                                BUCKET_NAME="''' + bucketName + '''"

                                                echo "Checking if hierarchical folder structure ${HIERARCHICAL_PATH} exists in bucket ${BUCKET_NAME}..."
                                                if gsutil ls gs://${BUCKET_NAME}/${HIERARCHICAL_PATH}/ 2>/dev/null | grep -q .; then
                                                    echo "Build folder ${HIERARCHICAL_PATH} already exists"
                                                else
                                                    echo "Build folder ${HIERARCHICAL_PATH} does not exist, will be created during upload"
                                                fi

                                                echo "Uploading acunetix_report.pdf to gs://${BUCKET_NAME}/''' + objectPath + '''"
                                                echo "Folder structure: ''' + projectname + ''' -> ''' + branchName + ''' -> ''' + jenkinsBuildFolder + '''"
                                                gsutil cp acunetix_report.pdf gs://${BUCKET_NAME}/''' + objectPath + '''
                                                echo "Upload completed successfully!"
                                            '''

                                            def gcsUrl = "https://console.cloud.google.com/storage/browser/${bucketName}/${hierarchicalPath}/"
                                            env.ACUNETIX_REPORT_URL = gcsUrl

                                            echo "Report uploaded successfully!"
                                            echo "Report URL: ${gcsUrl}"

                                            writeFile file: 'report-url.txt', text: gcsUrl
                                            archiveArtifacts artifacts: 'report-url.txt', allowEmptyArchive: true

                                            def apiUrl = "https://mpaasdev.m-devsecops.com/api/v1/organizations/mahindra-dev/projects/${projectname}/environment/${ENVIRONMENT}/jenkins/ccf/upload"
                                            def requestBody = """{
                                                "job_name": "${job_name}",
                                                "security_url": "${gcsUrl}"
                                            }"""
                                            echo "Uploading report URL to CCF Security API: ${apiUrl}"
                                            def apiResponse = httpRequest(
                                                httpMode: 'PUT',
                                                url: apiUrl,
                                                contentType: 'APPLICATION_JSON',
                                                requestBody: requestBody,
                                                validResponseCodes: '200:599',
                                                consoleLogResponseBody: true
                                            )
                                        } catch (Exception err) {
                                            echo "Error in Uploading Report to mPaas stage: ${err}"
                                            currentBuild.result = 'FAILURE'
                                            throw err
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } catch (Exception err) {
        failedStage = "${pipelineStage ?: 'Unknown'}"
        echo "Failed at ${failedStage}"
        println(err)
        currentBuild.result = 'FAILURE'
        echo "Error caught"
    }
}

// FIXED: Corrected Acunetix vulnerability report generation
def generateAcunetixVulnerabilityReport(apiUrl, scanId, resultId) {
    try {
        withCredentials([string(credentialsId: 'ACUNETIX_API_KEY', variable: 'MyAPIKEY')]) {
            echo "=== GENERATING ACUNETIX VULNERABILITY REPORT ==="
            echo "Scan ID: ${scanId}, Result ID: ${resultId}"
            
            // First, let's check what the actual API response looks like
            def debugResponse = sh(
                script: "curl -sS -k -X GET \"${apiUrl}/scans/${scanId}/results/${resultId}/statistics\" -H \"Accept: application/json\" -H \"X-Auth: ${MyAPIKEY}\"",
                returnStdout: true
            ).trim()
            
            echo "DEBUG - Raw API Response: ${debugResponse}"
            
            // Try to parse the response
            def criticalCount = 0
            def highCount = 0
            def mediumCount = 0
            def lowCount = 0
            def infoCount = 0
            def totalCount = 0
            
            try {
                def stats = readJSON text: debugResponse
                echo "DEBUG - Parsed JSON structure: ${stats}"
                
                // Try different possible JSON structures for Acunetix API
                if (stats.severity_counts) {
                    // Structure 1: severity_counts object
                    criticalCount = stats.severity_counts.critical ?: 0
                    highCount = stats.severity_counts.high ?: 0
                    mediumCount = stats.severity_counts.medium ?: 0
                    lowCount = stats.severity_counts.low ?: 0
                    infoCount = stats.severity_counts.info ?: 0
                    echo "✓ Found vulnerability counts in severity_counts"
                } else if (stats.vulnerabilities) {
                    // Structure 2: vulnerabilities object
                    criticalCount = stats.vulnerabilities.critical ?: 0
                    highCount = stats.vulnerabilities.high ?: 0
                    mediumCount = stats.vulnerabilities.medium ?: 0
                    lowCount = stats.vulnerabilities.low ?: 0
                    infoCount = stats.vulnerabilities.info ?: 0
                    echo "✓ Found vulnerability counts in vulnerabilities"
                } else if (stats.severity) {
                    // Structure 3: severity object
                    criticalCount = stats.severity.critical ?: 0
                    highCount = stats.severity.high ?: 0
                    mediumCount = stats.severity.medium ?: 0
                    lowCount = stats.severity.low ?: 0
                    infoCount = stats.severity.info ?: 0
                    echo "✓ Found vulnerability counts in severity"
                } else {
                    // Fallback: Try to extract from any numeric fields
                    def responseText = debugResponse.toLowerCase()
                    criticalCount = extractCountFromText(responseText, "critical")
                    highCount = extractCountFromText(responseText, "high")
                    mediumCount = extractCountFromText(responseText, "medium")
                    lowCount = extractCountFromText(responseText, "low")
                    infoCount = extractCountFromText(responseText, "info")
                    echo "✓ Extracted counts using text parsing"
                }
                
                totalCount = criticalCount + highCount + mediumCount + lowCount + infoCount
                
            } catch (Exception jsonException) {
                echo "⚠ JSON parsing failed, using text extraction: ${jsonException.message}"
                // Fallback to text extraction
                def responseText = debugResponse.toLowerCase()
                criticalCount = extractCountFromText(responseText, "critical")
                highCount = extractCountFromText(responseText, "high")
                mediumCount = extractCountFromText(responseText, "medium")
                lowCount = extractCountFromText(responseText, "low")
                infoCount = extractCountFromText(responseText, "info")
                totalCount = criticalCount + highCount + mediumCount + lowCount + infoCount
            }
            
            // Final fallback - if all counts are zero, try alternative API endpoint
            if (totalCount == 0) {
                echo "⚠ No vulnerabilities found in statistics, trying vulnerabilities endpoint..."
                try {
                    def vulnerabilitiesResponse = sh(
                        script: "curl -sS -k -X GET \"${apiUrl}/scans/${scanId}/results/${resultId}/vulnerabilities\" -H \"Accept: application/json\" -H \"X-Auth: ${MyAPIKEY}\"",
                        returnStdout: true
                    ).trim()
                    
                    echo "DEBUG - Vulnerabilities API Response: ${vulnerabilitiesResponse}"
                    
                    if (vulnerabilitiesResponse.contains("vulnerabilities")) {
                        def vulnsData = readJSON text: vulnerabilitiesResponse
                        if (vulnsData.vulnerabilities && vulnsData.vulnerabilities.size() > 0) {
                            // Count vulnerabilities by severity
                            vulnsData.vulnerabilities.each { vuln ->
                                def severity = vuln.severity?.toString()?.toLowerCase() ?: "info"
                                switch (severity) {
                                    case "critical": criticalCount++; break
                                    case "high": highCount++; break
                                    case "medium": mediumCount++; break
                                    case "low": lowCount++; break
                                    default: infoCount++; break
                                }
                            }
                            totalCount = criticalCount + highCount + mediumCount + lowCount + infoCount
                            echo "✓ Counted ${totalCount} vulnerabilities from vulnerabilities endpoint"
                        }
                    }
                } catch (Exception vulnException) {
                    echo "⚠ Vulnerabilities endpoint also failed: ${vulnException.message}"
                }
            }
            
            // Create the vulnerability count report
            def dateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
            def reportContent = """ACUNETIX DAST VULNERABILITY SCAN SUMMARY
========================================================================
Scan Date: ${dateTime}
Build: ${env.JOB_NAME} #${env.BUILD_NUMBER}
Scan ID: ${scanId}
Result ID: ${resultId}

VULNERABILITY COUNTS:
Critical: ${criticalCount}
High: ${highCount}
Medium: ${mediumCount}
Low: ${lowCount}
Information: ${infoCount}
Total: ${totalCount}

STATUS: ${criticalCount > 0 || highCount > 0 ? "ATTENTION REQUIRED" : "ACCEPTABLE"}
========================================================================
"""
            
            // Write the report file
            writeFile file: 'vulnerability_count_acunetix.txt', text: reportContent
            archiveArtifacts artifacts: 'vulnerability_count_acunetix.txt', allowEmptyArchive: true
            
            echo "=== ACUNETIX VULNERABILITY SUMMARY ==="
            echo "Critical: ${criticalCount}"
            echo "High: ${highCount}"
            echo "Medium: ${mediumCount}"
            echo "Low: ${lowCount}"
            echo "Information: ${infoCount}"
            echo "Total: ${totalCount}"
            echo "================================"
            
            // Set build status based on vulnerability counts
            if (criticalCount > 0) {
                currentBuild.result = 'UNSTABLE'
                echo "❌ Critical vulnerabilities found (${criticalCount}) - marking build as UNSTABLE"
            } else if (highCount > 10) {
                currentBuild.result = 'UNSTABLE'
                echo "⚠ High number of high-severity vulnerabilities (${highCount}) - marking build as UNSTABLE"
            } else {
                echo "✅ Vulnerability scan passed - no critical vulnerabilities found"
            }
        }
        
    } catch (Exception e) {
        echo "❌ Error generating Acunetix vulnerability report: ${e.message}"
        e.printStackTrace()
        
        // Create a fallback report with error information
        def errorReport = """ACUNETIX DAST VULNERABILITY SCAN SUMMARY
========================================================================
Scan Date: ${new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())}
Build: ${env.JOB_NAME} #${env.BUILD_NUMBER}
Status: ERROR - Could not generate vulnerability report

ERROR: ${e.message}

Please check Acunetix dashboard manually for scan results.
========================================================================
"""
        writeFile file: 'vulnerability_count_acunetix.txt', text: errorReport
        archiveArtifacts artifacts: 'vulnerability_count_acunetix.txt', allowEmptyArchive: true
        
        currentBuild.result = 'UNSTABLE'
    }
}

// Helper function to extract count from text
def extractCountFromText(text, severity) {
    try {
        // Try pattern: "severity": number
        def pattern1 = ~/"${severity}"\\s*:\\s*(\\d+)/
        def matcher1 = pattern1.matcher(text)
        if (matcher1.find()) {
            return matcher1.group(1).toInteger()
        }
        
        // Try pattern: 'severity': number
        def pattern2 = ~/'${severity}'\\s*:\\s*(\\d+)/
        def matcher2 = pattern2.matcher(text)
        if (matcher2.find()) {
            return matcher2.group(1).toInteger()
        }
        
        // Try pattern: severity: number
        def pattern3 = ~/${severity}\\s*:\\s*(\\d+)/
        def matcher3 = pattern3.matcher(text)
        if (matcher3.find()) {
            return matcher3.group(1).toInteger()
        }
        
        return 0
    } catch (Exception e) {
        echo "⚠ Error extracting ${severity} count: ${e.message}"
        return 0
    }
}