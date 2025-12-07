import java.text.SimpleDateFormat
import hudson.model.Actionable
import hudson.model.Result

def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def AGENT = config.AGENT
    def MAVEN_SETTINGS = config.MAVEN_SETTINGS
    def git = new org.mahindra.Git()
    def scanname = "${env.JOB_NAME}jenkins_build"

    try {
        podTemplate(
            nodeSelector: 'cloud.google.com/gke-nodepool=spot-node-pool-1',
            containers: [containerTemplate(name: 'jnlp', image: 'asia.gcr.io/common-infra-services/jenkins-agent-updated:mss-cli')],
            volumes: [
                hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock'),
                persistentVolumeClaim(claimName: 'jenkinsagentpvc', mountPath: '/home/jenkins/agent', readOnly: false)
            ]) {
            node(POD_LABEL) {
                currentBuild.result = 'SUCCESS'
                stage('IAC Scan: Checkov') {
                    withCredentials([
                        string(credentialsId: 'CheckovKey', variable: 'API_Key'),
                        string(credentialsId: "PrismaUsername1", variable: 'PC_User'),
                        string(credentialsId: "PrismaPassword1", variable: 'PC_Password')
                    ]) {
                        container('jnlp') {
                            // Checkout source code
                            pipelineStage = "${STAGE_NAME}"
                            git.gitCheckout(5)
                            echo "Status ${currentBuild.result}"
                            echo "Source code checked out successfully"
                            
                            // Continue with Checkov scan
                            sh "ls -lh"
                            sh "sed -i '/kubernetes/d' /etc/apt/sources.list.d/kubernetes.list"
                            sh "apt-get update -y"
                            sh "apt-get install python3-pip -y"
                            sh "python3 -m pip install pipenv"
                            sh "pipenv install checkov"
                            try {
                                sh """
                                    pipenv run checkov -d . \
                                    --bc-api-key ${PC_User}::${PC_Password} \
                                    -o json -o junitxml \
                                    --output-file-path . \
                                    --repo-id '${scanname}' \
                                    --prisma-api-url https://api.ind.prismacloud.io
                                """
                            } catch(Exception e) {
                                echo "Error occurred during Checkov scan: ${e}"
                            }
                            sh "ls -lrt"
                            
                            // Rename JSON file
                            sh "mv results_json.json checkov_report.json"
                            
                            // Archive renamed JSON report
                            archiveArtifacts 'checkov_report.json'
                            
                            // Generate vulnerability count report for Checkov
                            generateCheckovVulnerabilityReport()

                            // Stash reports
                            stash includes: 'checkov_report.json,vulnerability_count_checkov.txt', name: 'checkov-reports'
                        }
                    }
                }
            }
        }
    } catch (Exception err) {
        println err
        echo "Build Failed at Checkov scan stage"
        currentBuild.result = 'FAILURE'
        echo "Error caught: ${err.message}"
    }
}

// Fixed reusable function to generate Checkov vulnerability report (matching Prisma pattern)
def generateCheckovVulnerabilityReport() {
    try {
        sh '''
            #!/bin/bash
            echo "=== CHECKOV IAC VULNERABILITY SCAN RESULTS ==="
            echo "Report file: ${WORKSPACE}/checkov_report.json"
            
            # Check if file exists
            if [ ! -f "${WORKSPACE}/checkov_report.json" ]; then
                echo "Error: Results file not found!"
                exit 1
            fi
            
            echo "Parsing JSON results..."
            
            # Install jq if not available (needed for JSON parsing)
            if ! command -v jq &> /dev/null; then
                echo "Installing jq for JSON parsing..."
                apt-get update -qq && apt-get install -y jq 2>&1 | grep -v "^debconf" || echo "Failed to install jq"
            fi
            
            # Extract vulnerability counts with proper error handling
            if command -v jq &> /dev/null; then
                echo "Using jq to parse JSON..."
                
                # Try the actual structure from your JSON: .results.failed_checks[]
                CRITICAL=$(jq -r '[.results.failed_checks[]? | select(.severity == "CRITICAL" or .severity == "critical")] | length' "${WORKSPACE}/checkov_report.json" 2>/dev/null || echo "0")
                HIGH=$(jq -r '[.results.failed_checks[]? | select(.severity == "HIGH" or .severity == "high")] | length' "${WORKSPACE}/checkov_report.json" 2>/dev/null || echo "0")
                MEDIUM=$(jq -r '[.results.failed_checks[]? | select(.severity == "MEDIUM" or .severity == "medium")] | length' "${WORKSPACE}/checkov_report.json" 2>/dev/null || echo "0")
                LOW=$(jq -r '[.results.failed_checks[]? | select(.severity == "LOW" or .severity == "low")] | length' "${WORKSPACE}/checkov_report.json" 2>/dev/null || echo "0")
                TOTAL=$(jq -r '[.results.failed_checks[]?] | length' "${WORKSPACE}/checkov_report.json" 2>/dev/null || echo "0")
                
                echo "First attempt - Direct .results.failed_checks path:"
                echo "Critical: ${CRITICAL}, High: ${HIGH}, Medium: ${MEDIUM}, Low: ${LOW}, Total: ${TOTAL}"
                
                # If still zero, try array format: .[].results.failed_checks[]
                if [ "$TOTAL" = "0" ] || [ "$TOTAL" = "null" ]; then
                    echo "Trying array format..."
                    CRITICAL=$(jq -r '[.[].results.failed_checks[]? | select(.severity == "CRITICAL" or .severity == "critical")] | length' "${WORKSPACE}/checkov_report.json" 2>/dev/null || echo "0")
                    HIGH=$(jq -r '[.[].results.failed_checks[]? | select(.severity == "HIGH" or .severity == "high")] | length' "${WORKSPACE}/checkov_report.json" 2>/dev/null || echo "0")
                    MEDIUM=$(jq -r '[.[].results.failed_checks[]? | select(.severity == "MEDIUM" or .severity == "medium")] | length' "${WORKSPACE}/checkov_report.json" 2>/dev/null || echo "0")
                    LOW=$(jq -r '[.[].results.failed_checks[]? | select(.severity == "LOW" or .severity == "low")] | length' "${WORKSPACE}/checkov_report.json" 2>/dev/null || echo "0")
                    TOTAL=$(jq -r '[.[].results.failed_checks[]?] | length' "${WORKSPACE}/checkov_report.json" 2>/dev/null || echo "0")
                    
                    echo "Second attempt - Array format:"
                    echo "Critical: ${CRITICAL}, High: ${HIGH}, Medium: ${MEDIUM}, Low: ${LOW}, Total: ${TOTAL}"
                fi
                
                # If still zero but we have failed_checks, try summary format
                if [ "$TOTAL" = "0" ] || [ "$TOTAL" = "null" ]; then
                    echo "Trying summary format..."
                    CRITICAL=$(jq -r '.summary.failed_critical // .results.summary.failed_critical // 0' "${WORKSPACE}/checkov_report.json" 2>/dev/null || echo "0")
                    HIGH=$(jq -r '.summary.failed_high // .results.summary.failed_high // 0' "${WORKSPACE}/checkov_report.json" 2>/dev/null || echo "0")
                    MEDIUM=$(jq -r '.summary.failed_medium // .results.summary.failed_medium // 0' "${WORKSPACE}/checkov_report.json" 2>/dev/null || echo "0")
                    LOW=$(jq -r '.summary.failed_low // .results.summary.failed_low // 0' "${WORKSPACE}/checkov_report.json" 2>/dev/null || echo "0")
                    TOTAL=$(jq -r '.summary.failed // .results.summary.failed // 0' "${WORKSPACE}/checkov_report.json" 2>/dev/null || echo "0")
                    
                    echo "Third attempt - Summary format:"
                    echo "Critical: ${CRITICAL}, High: ${HIGH}, Medium: ${MEDIUM}, Low: ${LOW}, Total: ${TOTAL}"
                fi
            else
                echo "Warning: jq not available, using grep fallback..."
                # Fallback using grep (less accurate but works without jq)
                CRITICAL=$(grep -i '"severity"[[:space:]]*:[[:space:]]*"CRITICAL"' "${WORKSPACE}/checkov_report.json" | wc -l || echo "0")
                HIGH=$(grep -i '"severity"[[:space:]]*:[[:space:]]*"HIGH"' "${WORKSPACE}/checkov_report.json" | wc -l || echo "0")
                MEDIUM=$(grep -i '"severity"[[:space:]]*:[[:space:]]*"MEDIUM"' "${WORKSPACE}/checkov_report.json" | wc -l || echo "0")
                LOW=$(grep -i '"severity"[[:space:]]*:[[:space:]]*"LOW"' "${WORKSPACE}/checkov_report.json" | wc -l || echo "0")
                TOTAL=$(grep -c '"severity"' "${WORKSPACE}/checkov_report.json" || echo "0")
            fi
            
            # Clean up any null or empty values
            CRITICAL=$(echo "$CRITICAL" | grep -o '[0-9]\\+' | head -1 || echo "0")
            HIGH=$(echo "$HIGH" | grep -o '[0-9]\\+' | head -1 || echo "0")
            MEDIUM=$(echo "$MEDIUM" | grep -o '[0-9]\\+' | head -1 || echo "0")
            LOW=$(echo "$LOW" | grep -o '[0-9]\\+' | head -1 || echo "0")
            TOTAL=$(echo "$TOTAL" | grep -o '[0-9]\\+' | head -1 || echo "0")
            
            # Set defaults if empty
            CRITICAL=${CRITICAL:-0}
            HIGH=${HIGH:-0}
            MEDIUM=${MEDIUM:-0}
            LOW=${LOW:-0}
            TOTAL=${TOTAL:-0}
            
            echo "Final extracted vulnerability counts:"
            echo "Critical: ${CRITICAL}"
            echo "High: ${HIGH}"
            echo "Medium: ${MEDIUM}"
            echo "Low: ${LOW}"
            echo "Total: ${TOTAL}"
            
            # Create vulnerability_count_checkov.txt
            cat > "${WORKSPACE}/vulnerability_count_checkov.txt" << EOF
CHECKOV IAC VULNERABILITY SCAN SUMMARY
Scan Date: $(date)
Build: ${JOB_NAME} #${BUILD_NUMBER}

VULNERABILITY COUNTS:
Critical: ${CRITICAL}
High: ${HIGH}
Medium: ${MEDIUM}
Low: ${LOW}
Total: ${TOTAL}

STATUS: $(if [ "${CRITICAL}" -gt 0 ] || [ "${HIGH}" -gt 0 ]; then echo "ATTENTION REQUIRED"; else echo "ACCEPTABLE"; fi)
EOF
            
            echo "Vulnerability report generated successfully"
        '''
        
        // Parse results and set build status using Jenkins native JSON parsing
        try {
            def checkovScanResult = readJSON file: "${WORKSPACE}/checkov_report.json"
            def reportMap = [critical: 0, high: 0, medium: 0, low: 0, total: 0]
            
            // Check for direct .results.failed_checks format (your actual JSON structure)
            if (checkovScanResult.results?.failed_checks) {
                checkovScanResult.results.failed_checks.each { check ->
                    def severity = check.severity?.toString()?.toUpperCase()
                    switch(severity) {
                        case 'CRITICAL':
                            reportMap.critical++
                            break
                        case 'HIGH':
                            reportMap.high++
                            break
                        case 'MEDIUM':
                            reportMap.medium++
                            break
                        case 'LOW':
                            reportMap.low++
                            break
                    }
                    reportMap.total++
                }
            }
            // Check for array format: .[].results.failed_checks
            else if (checkovScanResult instanceof List && checkovScanResult.size() > 0) {
                checkovScanResult.each { result ->
                    if (result.results?.failed_checks) {
                        result.results.failed_checks.each { check ->
                            def severity = check.severity?.toString()?.toUpperCase()
                            switch(severity) {
                                case 'CRITICAL':
                                    reportMap.critical++
                                    break
                                case 'HIGH':
                                    reportMap.high++
                                    break
                                case 'MEDIUM':
                                    reportMap.medium++
                                    break
                                case 'LOW':
                                    reportMap.low++
                                    break
                            }
                            reportMap.total++
                        }
                    }
                }
            }
            // Fallback to summary format
            else if (checkovScanResult.summary) {
                reportMap = [
                    critical: checkovScanResult.summary.failed_critical ?: 0,
                    high: checkovScanResult.summary.failed_high ?: 0,
                    medium: checkovScanResult.summary.failed_medium ?: 0,
                    low: checkovScanResult.summary.failed_low ?: 0,
                    total: checkovScanResult.summary.failed ?: 0
                ]
            } else if (checkovScanResult.results?.summary) {
                reportMap = [
                    critical: checkovScanResult.results.summary.failed_critical ?: 0,
                    high: checkovScanResult.results.summary.failed_high ?: 0,
                    medium: checkovScanResult.results.summary.failed_medium ?: 0,
                    low: checkovScanResult.results.summary.failed_low ?: 0,
                    total: checkovScanResult.results.summary.failed ?: 0
                ]
            }
            
            echo "Checkov scan completed. Critical: ${reportMap.critical}, High: ${reportMap.high}, Medium: ${reportMap.medium}, Low: ${reportMap.low}, Total: ${reportMap.total}"
            
            // Archive the vulnerability report
            archiveArtifacts artifacts: 'vulnerability_count_checkov.txt', allowEmptyArchive: true
            
            // Set build status based on vulnerability counts (matching Prisma logic)
            if (reportMap.critical > 0) {
                currentBuild.result = 'UNSTABLE'
                echo "Critical vulnerabilities found (${reportMap.critical}) - marking build as UNSTABLE"
            } else if (reportMap.high > 10) {
                currentBuild.result = 'UNSTABLE'
                echo "High number of high-severity vulnerabilities (${reportMap.high}) - marking build as UNSTABLE"
            } else {
                echo "Vulnerability scan passed - no critical vulnerabilities found"
            }
            
        } catch (Exception jsonException) {
            echo "Warning: Could not parse JSON results for detailed analysis: ${jsonException.message}"
            jsonException.printStackTrace()
            // Fallback to basic shell-based analysis
            def vulnerabilityReport = readFile "${WORKSPACE}/vulnerability_count_checkov.txt"
            echo "Vulnerability report content:\n${vulnerabilityReport}"
        }
        
    } catch (Exception e) {
        echo "Error generating vulnerability report: ${e.message}"
        e.printStackTrace()
        currentBuild.result = 'UNSTABLE'
    }
}