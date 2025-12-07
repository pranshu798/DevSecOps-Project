import java.text.SimpleDateFormat
import hudson.model.Actionable
import hudson.model.Result

def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def AGENT = config.AGENT
    def APP_NAME = config.APP_NAME
    def PRESET = config.PRESET_NAME ?: "Checkmarx Default"
    def MAVEN_SETTINGS = config.MAVEN_SETTINGS
    def git = new org.mahindra.Git()
    def job_name = env.JOB_NAME.trim()

    try {
        podTemplate(
                nodeSelector: 'cloud.google.com/gke-nodepool=spot-node-pool-1',
                containers: [containerTemplate(name: 'jnlp', image: 'asia.gcr.io/common-infra-services/jenkins-agent-updated:latest')],
                envVars: [envVar(key: 'JAVA_OPTS', value: '-Xms12g -Xmx12g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:InitiatingHeapOccupancyPercent=70')],
                volumes: [hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock'),
                          persistentVolumeClaim(claimName: 'jenkinsagentpvc', mountPath: '/home/jenkins/agent', readOnly: false)
                ]) {
            node(POD_LABEL) {
                currentBuild.result = 'SUCCESS'
                
                stage('SAST Scan: Checkmarx') {
                    withCredentials([
                            string(credentialsId: "CxUsername", variable: 'CxUsername'),
                            string(credentialsId: "CxPass", variable: 'CxPass'),
                            file(credentialsId: 'mahindracert', variable: 'secretFilePath'),
                            string(credentialsId: "keystore_password", variable: 'keystorePassword'),
                            file(credentialsId: 'gcp-service-account-key', variable: 'GOOGLE_APPLICATION_CREDENTIALS')
                    ]) {
                        container('jnlp') {
                            pipelineStage = "${STAGE_NAME}"
                            
                            // Checkout source code
                            echo "Checking out source code..."
                            sh "rm -rf source-code"
                            sh "mkdir -p source-code"
                            
                            dir('source-code') {
                                git.gitCheckout(5)
                            }
                            
                            echo "Source code checked out to: ${pwd()}/source-code"
                            
                            // Install dependencies and setup
                            sh "apt-get update && apt-get install unzip -y"
                            sh "free -m"
                            
                            // Clean any existing Checkmarx plugin and reports
                            sh "rm -rf cxpluginf SAST_Report.xml SAST_Report.pdf"
                            sh "cp $secretFilePath ./certificate.crt"
                            
                            try {
                                sh "keytool -importcert -file certificate.crt -keystore keystore.jks -alias \"Alias1\" -noprompt -storepass $keystorePassword"
                            } catch(Exception e) {
                                echo "Error occurred: ${e}"
                            }
                            
                            sh "mkdir -p cxpluginf; curl -o cxpluginf/cxplugin.zip https://download.checkmarx.com/9.5.0/Plugins/CxConsolePlugin-1.1.39.zip; unzip cxpluginf/cxplugin.zip -d cxpluginf/;"
                            sh "sed -i 's/scan.zip.max_size=200/scan.zip.max_size=1000/' cxpluginf/config/cx_console.properties"
                            
                            try {
                                def currentDir = sh(returnStdout: true, script: 'pwd').trim()
                                def sourceCodePath = "${currentDir}/source-code"
                                
                                // Set JVM options properly in the environment where the scan runs
                                withEnv([
                                    "CX_USERNAME=${CxUsername}", 
                                    "CX_PASSWORD=${CxPass}", 
                                    "JAVA_OPTS=-Xmx12g -Xms2g",
                                    "JAVA_TOOL_OPTIONS=-Xmx12g -Xms2g",
                                    "_JAVA_OPTIONS=-Xmx12g -Xms2g"
                                ]) {
                                    
                                    if (PRESET) {
                                        echo "Using preset: ${PRESET}"
                                    } else {
                                        echo "Preset variable is not set."
                                    }
                                    
                                    echo "Scanning source code from: ${sourceCodePath}"
                                    echo "Reports will be generated in: ${currentDir}"
                                    
                                    // Verify source code directory exists and has content
                                    sh "echo 'Source code directory contents:'; ls -la '${sourceCodePath}'"
                                    
                                    // Run Checkmarx scan with dedicated source folder
                                    sh """
                                        echo "Available memory before scan:"
                                        free -m
                                        echo "Java options set:"
                                        echo "JAVA_OPTS: \$JAVA_OPTS"
                                        echo "JAVA_TOOL_OPTIONS: \$JAVA_TOOL_OPTIONS"
                                        echo "_JAVA_OPTIONS: \$_JAVA_OPTIONS"
                                        
                                        cxpluginf/runCxConsole.sh Scan -v \\
                                            -CxServer https://checkmarx.mahindra-ad.com \\
                                            -ProjectName "CxServer/Jenkins/${APP_NAME}" \\
                                            -CxUser "\$CX_USERNAME" \\
                                            -CxPassword "\$CX_PASSWORD" \\
                                            -Preset "${PRESET}" \\
                                            -Incremental \\
                                            -LocationType folder \\
                                            -LocationPath "${sourceCodePath}" \\
                                            -TrustedCertificates \\
                                            -ReportXML "${currentDir}/SAST_Report.xml" \\
                                            -ReportPDF "${currentDir}/SAST_Report.pdf"
                                    """
                                    
                                    archiveArtifacts 'SAST_Report.xml'
                                    archiveArtifacts 'SAST_Report.pdf'
                                    
                                    // Generate vulnerability count report
                                    generateVulnerabilityReport("CxServer/Jenkins/${APP_NAME}")
                                    
                                    // Stash reports with new name
                                    stash includes: 'SAST_Report.xml,SAST_Report.pdf,vulnerability_count_sast.txt', name: 'sast-reports'
                                }
                            } catch(Exception e) {
                                echo "Checkmarx scan failed: ${e}"
                                throw e
                            }
                            
                            // Upload to mPaas if CCF is required
                            if (config.CCF_Required?.toLowerCase() == 'yes') {
                                echo "CCF Required - Uploading Report to mPaas..."
                                script {
                                    // Extract project name and branch from job_name (format: project/branch)
                                    def jobParts = job_name.split('/')
                                    def projectname = jobParts[0]
                                    def branchName = jobParts.size() > 1 ? jobParts[1] : 'main'
                                    
                                    // Create Jenkins-style build folder name using actual BUILD_NUMBER
                                    def jenkinsBuildFolder = "${projectname}jenkins_build${BUILD_NUMBER}"
                                    
                                    // Create hierarchical folder structure: ProjectName/BranchName/JenkinsBuildFolder
                                    def hierarchicalPath = "${projectname}/${branchName}/${jenkinsBuildFolder}"
                                    def objectPath = "${hierarchicalPath}/SAST_Report.pdf"
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

                                            echo "Uploading SAST_Report.pdf to gs://${BUCKET_NAME}/''' + objectPath + '''"
                                            echo "Folder structure: ''' + projectname + ''' -> ''' + branchName + ''' -> ''' + jenkinsBuildFolder + '''"
                                            gsutil cp SAST_Report.pdf gs://${BUCKET_NAME}/''' + objectPath + '''
                                            echo "Upload completed successfully!"
                                        '''

                                        def gcsUrl = "https://console.cloud.google.com/storage/browser/${bucketName}/${hierarchicalPath}/"
                                        env.SAST_REPORT_URL = gcsUrl

                                        echo "Report uploaded successfully!"
                                        echo "Report URL: ${gcsUrl}"

                                        writeFile file: 'report-url.txt', text: gcsUrl
                                        archiveArtifacts artifacts: 'report-url.txt', allowEmptyArchive: true

                                        // Prepare and send payload to mPaas API
                                        def apiUrl = "https://mpaasdev.m-devsecops.com/api/v1/organizations/mahindra-dev/projects/${projectname}/environment/${envName}/jenkins/ccf/upload"
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
                                        echo "Error in Uploading Report to mPaas: ${err}"
                                        currentBuild.result = 'FAILURE'
                                        throw err
                                    }
                                }
                            }
                            
                            echo "Status ${currentBuild.result}"
                        }
                    }
                }
            }
        }
    } catch (Exception err) {
        println err
        failedStage = "${pipelineStage}"
        echo "Error caught"
        currentBuild.result = 'UNSTABLE'
    }
}

// Fixed reusable function to generate vulnerability report from Checkmarx SAST XML
def generateVulnerabilityReport(projectName) {
    try {
        sh '''
            #!/bin/bash
            echo "=== CHECKMARX SAST VULNERABILITY SCAN RESULTS ==="
            echo "Report file: ${WORKSPACE}/SAST_Report.xml"
            
            # Check if file exists
            if [ ! -f "${WORKSPACE}/SAST_Report.xml" ]; then
                echo "Error: Results file not found!"
                exit 1
            fi
            
            echo "Parsing XML results..."
            
            # Extract vulnerability counts from Checkmarx XML with proper error handling
            # Checkmarx uses severity levels: High, Medium, Low, Information
            HIGH=$(grep -o 'Severity="High"' "${WORKSPACE}/SAST_Report.xml" | wc -l || echo "0")
            MEDIUM=$(grep -o 'Severity="Medium"' "${WORKSPACE}/SAST_Report.xml" | wc -l || echo "0")
            LOW=$(grep -o 'Severity="Low"' "${WORKSPACE}/SAST_Report.xml" | wc -l || echo "0")
            INFO=$(grep -o 'Severity="Information"' "${WORKSPACE}/SAST_Report.xml" | wc -l || echo "0")
            
            # Calculate total (excluding Information level for consistency)
            TOTAL=$((HIGH + MEDIUM + LOW))
            
            # Set defaults if empty
            HIGH=${HIGH:-0}
            MEDIUM=${MEDIUM:-0}
            LOW=${LOW:-0}
            INFO=${INFO:-0}
            TOTAL=${TOTAL:-0}
            
            echo "Extracted vulnerability counts:"
            echo "High: ${HIGH}"
            echo "Medium: ${MEDIUM}"
            echo "Low: ${LOW}"
            echo "Information: ${INFO}"
            echo "Total: ${TOTAL}"
            
            # Create vulnerability_count_sast.txt
            cat > "${WORKSPACE}/vulnerability_count_sast.txt" << EOF
CHECKMARX SAST VULNERABILITY SCAN SUMMARY
Project: ''' + projectName + '''
Scan Date: $(date)
Build: ${JOB_NAME} #${BUILD_NUMBER}

VULNERABILITY COUNTS:
High: ${HIGH}
Medium: ${MEDIUM}
Low: ${LOW}
Information: ${INFO}
Total: ${TOTAL}

STATUS: $(if [ "${HIGH}" -gt 0 ]; then echo "ATTENTION REQUIRED"; else echo "ACCEPTABLE"; fi)
EOF
            
            echo "Vulnerability report generated successfully"
        '''
        
        // Parse results and set build status using Jenkins native XML parsing
        try {
            def sastScanResult = readFile "${WORKSPACE}/SAST_Report.xml"
            
            // Count occurrences of each severity level
            def highCount = (sastScanResult =~ /Severity="High"/).count
            def mediumCount = (sastScanResult =~ /Severity="Medium"/).count
            def lowCount = (sastScanResult =~ /Severity="Low"/).count
            def infoCount = (sastScanResult =~ /Severity="Information"/).count
            def totalCount = highCount + mediumCount + lowCount
            
            def reportMap = [
                high: highCount ?: 0,
                medium: mediumCount ?: 0,
                low: lowCount ?: 0,
                information: infoCount ?: 0,
                total: totalCount ?: 0
            ]
            
            echo "Checkmarx SAST scan completed. High: ${reportMap.high}, Medium: ${reportMap.medium}, Low: ${reportMap.low}, Information: ${reportMap.information}, Total: ${reportMap.total}"
            
            // Archive the vulnerability report
            archiveArtifacts artifacts: 'vulnerability_count_sast.txt', allowEmptyArchive: true
            
            // Set build status based on vulnerability counts (matching Prisma logic)
            if (reportMap.high > 0) {
                currentBuild.result = 'UNSTABLE'
                echo "High severity vulnerabilities found (${reportMap.high}) - marking build as UNSTABLE"
            } else if (reportMap.medium > 10) {
                currentBuild.result = 'UNSTABLE'
                echo "High number of medium-severity vulnerabilities (${reportMap.medium}) - marking build as UNSTABLE"
            } else {
                echo "Vulnerability scan passed - no high severity vulnerabilities found"
            }
            
        } catch (Exception xmlException) {
            echo "Warning: Could not parse XML results for detailed analysis: ${xmlException.message}"
            // Fallback to basic shell-based analysis
            def vulnerabilityReport = readFile "${WORKSPACE}/vulnerability_count_sast.txt"
            echo "Vulnerability report content:\n${vulnerabilityReport}"
        }
        
    } catch (Exception e) {
        echo "Error generating vulnerability report: ${e.message}"
        currentBuild.result = 'UNSTABLE'
    }
}