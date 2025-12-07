import java.text.SimpleDateFormat
import java.util.Date

def call(body) {
    def config = [
        SNYK_CRITICAL_MAX: '0',
        SNYK_HIGH_MAX: '0',
        CHECKOV_CRITICAL_MAX: '0',
        CHECKOV_HIGH_MAX: '0',
        PRISMA_CRITICAL_MAX: '0',
        PRISMA_HIGH_MAX: '0',
        SAST_CRITICAL_MAX: '0',
        SAST_HIGH_MAX: '0',
        DAST_CRITICAL_MAX: '0',
        DAST_HIGH_MAX: '0',
        FAIL_ON_VULNS: true,
        GCS_BUCKET: 'bkt-modernization-dev-securityreports',  // FIXED: Use dev bucket for all environments
        BUCKET_PROJECT_NAME: 'modernization-dev-832770',      // FIXED: Use dev project
        GCP_KEY_FILE: '/home/jenkins/agent/key/key/mpaas-prod-all-access.json',
        SOURCE_BRANCH: 'gcp_uat'  // Source is UAT
    ]
    
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def pipelineStage = ""
    def sourceBuildNumber = "Unknown"
    def sourceEnvironment = config.SOURCE_BRANCH

    try {
        podTemplate(
            nodeSelector: 'cloud.google.com/gke-nodepool=spot-node-pool-1',
            containers: [containerTemplate(name: 'jnlp', image: 'asia.gcr.io/common-infra-services/jenkins-agent-updated:mss-cli')],
            volumes: [
                hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock'),
                persistentVolumeClaim(claimName: 'jenkinsagentpvc', mountPath: '/home/jenkins/agent', readOnly: false)
            ]
        ) {
            node(POD_LABEL) {
                currentBuild.result = 'SUCCESS'
                
                stage('Retrieve Vulnerability Reports from UAT') {
                    container('jnlp') {
                        pipelineStage = "${STAGE_NAME}"
                        
                        echo "=========================================================================="
                        echo "           RETRIEVING VULNERABILITY REPORTS FROM GCS (UAT → PROD)"
                        echo "=========================================================================="
                        echo "Current Job: ${env.JOB_NAME}"
                        echo "Current Build: #${env.BUILD_NUMBER}"
                        echo "Source Environment: ${config.SOURCE_BRANCH} (UAT)"
                        echo "Target Environment: PRODUCTION"
                        echo "Workspace: ${env.WORKSPACE}"
                        echo ""
                        
                        // Setup GCP authentication
                        echo "=== SETTING UP GCP AUTHENTICATION ==="
                        sh """
                            gcloud auth activate-service-account --key-file=${config.GCP_KEY_FILE}
                            gcloud config set project ${config.BUCKET_PROJECT_NAME}
                            echo "GCP authentication successful"
                        """
                        echo ""
                        
                        // Download reports from GCS UAT bucket
                        def downloadResult = downloadVulnerabilityReportsFromGCS(config, config.SOURCE_BRANCH)
                        
                        if (!downloadResult.success) {
                            echo "=========================================================================="
                            echo "CRITICAL ERROR: FAILED TO DOWNLOAD VULNERABILITY REPORTS FROM UAT"
                            echo "=========================================================================="
                            echo "Source: ${config.SOURCE_BRANCH} (UAT)"
                            echo "GCS Bucket: ${config.GCS_BUCKET}"
                            
                            if (downloadResult.error) {
                                echo "Error: ${downloadResult.error}"
                            }
                            
                            if (downloadResult.missingFiles) {
                                echo ""
                                echo "Missing Files:"
                                downloadResult.missingFiles.each { echo "  - ${it}" }
                            }
                            
                            echo ""
                            echo "TROUBLESHOOTING STEPS:"
                            echo "1. Verify gcp_uat pipeline completed successfully"
                            echo "2. Check that 'Upload Vulnerability Reports to GCS (UAT)' stage ran"
                            echo "3. Manually verify files exist in GCS bucket:"
                            echo "   gsutil ls gs://${config.GCS_BUCKET}/DevSecOps/${env.JOB_NAME.split('/')[0..-2].join('/')}/vulnerability_reports_uat/"
                            echo "=========================================================================="
                            
                            currentBuild.result = 'FAILURE'
                            error("Cannot proceed with production tollgate validation - no valid vulnerability reports found in GCS (vulnerability_reports_uat folder)")
                        }
                        
                        echo ""
                        echo "=== DOWNLOAD SUMMARY ==="
                        echo "Successfully downloaded: ${downloadResult.filesDownloaded} of 5 reports (including DAST) from UAT"
                        if (downloadResult.buildNumber) {
                            sourceBuildNumber = downloadResult.buildNumber
                            echo "Source UAT build number: ${sourceBuildNumber}"
                        }
                        
                        if (downloadResult.warnings && downloadResult.warnings.size() > 0) {
                            echo ""
                            echo "WARNINGS:"
                            downloadResult.warnings.each { echo "  ⚠ ${it}" }
                        }
                        echo ""
                        
                        // Verify files exist after download
                        echo "=== VERIFYING DOWNLOADED FILES ==="
                        sh '''
                            echo "Current directory: $(pwd)"
                            echo "Listing all vulnerability_count_*.txt files:"
                            ls -lah vulnerability_count_*.txt 2>/dev/null || echo "No files found!"
                        '''
                        echo ""
                        
                        // Display downloaded file contents
                        echo "=== DOWNLOADED FILE CONTENTS ==="
                        sh '''
                            for file in vulnerability_count_*.txt; do
                                if [ -f "$file" ]; then
                                    echo "=========================================="
                                    echo "FILE: $file"
                                    echo "SIZE: $(wc -c < "$file") bytes"
                                    echo "=========================================="
                                    cat "$file"
                                    echo ""
                                fi
                            done
                        '''
                    }
                }

                stage('Production Tollgate Validation') {
                    container('jnlp') {
                        pipelineStage = "${STAGE_NAME}"
                        
                        echo "=========================================================================="
                        echo "           PRODUCTION TOLLGATE VALIDATION (FROM UAT REPORTS)"
                        echo "=========================================================================="
                        
                        // Verify we're in the same workspace
                        echo "Current Workspace: ${env.WORKSPACE}"
                        sh '''
                            echo "Current directory: $(pwd)"
                            echo "Files in current directory:"
                            ls -lah vulnerability_count_*.txt 2>/dev/null || echo "WARNING: No vulnerability files found!"
                        '''
                        echo ""
                        
                        // Convert thresholds to integers
                        def thresholds = [
                            snyk: [
                                critical: config.SNYK_CRITICAL_MAX.toString().toInteger(),
                                high: config.SNYK_HIGH_MAX.toString().toInteger()
                            ],
                            checkov: [
                                critical: config.CHECKOV_CRITICAL_MAX.toString().toInteger(),
                                high: config.CHECKOV_HIGH_MAX.toString().toInteger()
                            ],
                            prisma: [
                                critical: config.PRISMA_CRITICAL_MAX.toString().toInteger(),
                                high: config.PRISMA_HIGH_MAX.toString().toInteger()
                            ],
                            sast: [
                                critical: config.SAST_CRITICAL_MAX.toString().toInteger(),
                                high: config.SAST_HIGH_MAX.toString().toInteger()
                            ],
                            acunetix: [  // DAST is named acunetix
                                critical: config.DAST_CRITICAL_MAX.toString().toInteger(),
                                high: config.DAST_HIGH_MAX.toString().toInteger()
                            ]
                        ]

                        echo "PRODUCTION THRESHOLDS CONFIGURED:"
                        thresholds.each { tool, limits ->
                            echo "  ${tool.toUpperCase().padRight(10)} | Critical: ${limits.critical}, High: ${limits.high}"
                        }
                        echo ""

                        // Initialize results
                        def scanResults = [:]
                        def overallPass = true
                        def failureReasons = []
                        def warnings = []
                        def dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        def currentDate = dateFormat.format(new Date())

                        // Process each tool's report (including DAST as acunetix)
                        ['snyk', 'checkov', 'prisma', 'sast', 'acunetix'].each { tool ->
                            def reportFile = "vulnerability_count_${tool}.txt"
                            
                            echo "=== PROCESSING ${tool.toUpperCase()} REPORT ==="
                            echo "File: ${reportFile}"
                            
                            // Double check file existence with shell command
                            def fileCheck = sh(script: "test -f ${reportFile} && echo 'EXISTS' || echo 'MISSING'", returnStdout: true).trim()
                            echo "Shell check result: ${fileCheck}"
                            
                            if (!fileExists(reportFile) || fileCheck == 'MISSING') {
                                echo "✗ ERROR: Report file not found"
                                echo "Attempted path: ${env.WORKSPACE}/${reportFile}"
                                
                                // Try to find the file
                                sh """
                                    echo "Searching for ${reportFile}..."
                                    find ${env.WORKSPACE} -name "${reportFile}" -type f 2>/dev/null || echo "File not found anywhere in workspace"
                                """
                                
                                scanResults[tool] = [
                                    critical: -1,
                                    high: -1,
                                    medium: -1,
                                    low: -1,
                                    total: -1,
                                    passed: false,
                                    error: "Report file not found"
                                ]
                                failureReasons << "${tool.toUpperCase()} - Report file not found"
                                overallPass = false
                                echo ""
                                return
                            }
                            
                            try {
                                def reportContent = readFile(reportFile).trim()
                                
                                if (reportContent.isEmpty()) {
                                    echo "✗ ERROR: Report file is empty"
                                    scanResults[tool] = [
                                        critical: -1,
                                        high: -1,
                                        medium: -1,
                                        low: -1,
                                        total: -1,
                                        passed: false,
                                        error: "Report file is empty"
                                    ]
                                    failureReasons << "${tool.toUpperCase()} - Report file is empty"
                                    overallPass = false
                                    echo ""
                                    return
                                }
                                
                                // Parse vulnerability counts with flexible regex
                                def criticalMatch = (reportContent =~ /(?i)critical\s*:?\s*(\d+)/)
                                def highMatch = (reportContent =~ /(?i)high\s*:?\s*(\d+)/)
                                def mediumMatch = (reportContent =~ /(?i)medium\s*:?\s*(\d+)/)
                                def lowMatch = (reportContent =~ /(?i)low\s*:?\s*(\d+)/)
                                def infoMatch = (reportContent =~ /(?i)information\s*:?\s*(\d+)/)
                                
                                def criticalCount = criticalMatch ? criticalMatch[0][1].toInteger() : 0
                                def highCount = highMatch ? highMatch[0][1].toInteger() : 0
                                def mediumCount = mediumMatch ? mediumMatch[0][1].toInteger() : 0
                                def lowCount = lowMatch ? lowMatch[0][1].toInteger() : 0
                                def infoCount = infoMatch ? infoMatch[0][1].toInteger() : 0
                                
                                // Calculate total, including information if present
                                def totalCount = criticalCount + highCount + mediumCount + lowCount
                                if (infoCount > 0) {
                                    totalCount += infoCount
                                }
                                
                                echo "Parsed Counts:"
                                echo "  Critical:    ${criticalCount}"
                                echo "  High:        ${highCount}"
                                echo "  Medium:      ${mediumCount}"
                                echo "  Low:         ${lowCount}"
                                if (infoCount > 0) {
                                    echo "  Information: ${infoCount}"
                                }
                                echo "  Total:       ${totalCount}"
                                
                                // Check for placeholder or wrong environment
                                if (reportContent.contains("NO SCAN DATA AVAILABLE") || reportContent.contains("placeholder")) {
                                    warnings << "${tool.toUpperCase()} report appears to be a placeholder"
                                    echo "⚠ WARNING: This appears to be a placeholder report"
                                }
                                
                                if (reportContent.contains("gcp_dev") && !reportContent.contains("gcp_uat")) {
                                    warnings << "${tool.toUpperCase()} report is from gcp_dev, not gcp_uat"
                                    echo "⚠ WARNING: Report is from gcp_dev, not gcp_uat"
                                }
                                
                                // Check if SAST has note about no Critical severity
                                if (tool == 'sast' && reportContent.contains("does not have Critical severity")) {
                                    echo "ℹ INFO: Checkmarx SAST does not have Critical severity level"
                                }
                                
                                scanResults[tool] = [
                                    critical: criticalCount,
                                    high: highCount,
                                    medium: mediumCount,
                                    low: lowCount,
                                    info: infoCount,
                                    total: totalCount,
                                    passed: true,
                                    error: null
                                ]

                                // Validate against thresholds
                                def toolPassed = true
                                
                                if (criticalCount > thresholds[tool].critical) {
                                    scanResults[tool].passed = false
                                    toolPassed = false
                                    overallPass = false
                                    failureReasons << "${tool.toUpperCase()} CRITICAL: ${criticalCount} > ${thresholds[tool].critical} (threshold)"
                                    echo "✗ FAILED: Critical count exceeds threshold"
                                } else {
                                    echo "✓ PASS: Critical (${criticalCount}) ≤ ${thresholds[tool].critical}"
                                }
                                
                                if (highCount > thresholds[tool].high) {
                                    scanResults[tool].passed = false
                                    toolPassed = false
                                    overallPass = false
                                    failureReasons << "${tool.toUpperCase()} HIGH: ${highCount} > ${thresholds[tool].high} (threshold)"
                                    echo "✗ FAILED: High count exceeds threshold"
                                } else {
                                    echo "✓ PASS: High (${highCount}) ≤ ${thresholds[tool].high}"
                                }
                                
                                if (toolPassed) {
                                    echo "✓ ${tool.toUpperCase()} OVERALL: PASSED"
                                } else {
                                    echo "✗ ${tool.toUpperCase()} OVERALL: FAILED"
                                }
                                
                            } catch (Exception e) {
                                echo "✗ ERROR processing report: ${e.getMessage()}"
                                e.printStackTrace()
                                scanResults[tool] = [
                                    critical: -1,
                                    high: -1,
                                    medium: -1,
                                    low: -1,
                                    info: -1,
                                    total: -1,
                                    passed: false,
                                    error: e.getMessage()
                                ]
                                failureReasons << "${tool.toUpperCase()} - Processing error: ${e.getMessage()}"
                                overallPass = false
                            }
                            
                            echo ""
                        }

                        // Generate validation report
                        def report = generateValidationReport(
                            currentDate,
                            config,
                            thresholds,
                            scanResults,
                            overallPass,
                            failureReasons,
                            warnings,
                            sourceBuildNumber,
                            sourceEnvironment
                        )

                        // Save report
                        writeFile file: 'tollgate_validation_report_prod.txt', text: report
                        archiveArtifacts artifacts: 'tollgate_validation_report_prod.txt', allowEmptyArchive: true
                        
                        echo ""
                        echo "=========================================================================="
                        echo report
                        echo "=========================================================================="

                        // Set build result
                        if (overallPass) {
                            currentBuild.result = 'SUCCESS'
                            echo ""
                            echo "✓ SUCCESS: PRODUCTION TOLLGATE VALIDATION PASSED"
                            echo "All vulnerability counts from UAT are within acceptable thresholds"
                            echo "Code is approved for production deployment"
                            echo ""
                        } else {
                            echo ""
                            echo "✗ FAILED: PRODUCTION TOLLGATE VALIDATION FAILED"
                            echo ""
                            echo "Failure Reasons:"
                            failureReasons.each { echo "  • ${it}" }
                            echo ""
                            
                            if (config.FAIL_ON_VULNS) {
                                currentBuild.result = 'FAILURE'
                                error("Production tollgate validation failed - vulnerabilities from UAT exceed configured thresholds")
                            } else {
                                currentBuild.result = 'UNSTABLE'
                                echo "Build marked as UNSTABLE (FAIL_ON_VULNS=false)"
                            }
                        }
                    }
                }
            }
        }
    } catch (Exception err) {
        echo "ERROR in Production Tollgate Validation"
        echo "Stage: ${pipelineStage}"
        echo "Message: ${err.getMessage()}"
        err.printStackTrace()
        currentBuild.result = 'FAILURE'
        throw err
    }
}

// Download vulnerability reports from GCS (vulnerability_reports_uat folder)
def downloadVulnerabilityReportsFromGCS(config, sourceBranch) {
    try {
        def jobParts = env.JOB_NAME.split('/')
        def jobNameClean = jobParts[0..-2].join('/')
        
        echo "Downloading reports from GCS for Production validation..."
        echo "Job: ${jobNameClean}"
        echo "Source: ${sourceBranch} (UAT)"
        echo "Bucket: ${config.GCS_BUCKET}"
        
        // UAT folder structure: vulnerability_reports_uat
        def vulnerabilityReportsPath = "DevSecOps/${jobNameClean}/vulnerability_reports_uat"
        def fullGcsPath = "gs://${config.GCS_BUCKET}/${vulnerabilityReportsPath}/"
        
        echo "GCS Path: ${fullGcsPath}"
        echo "Download Location: ${env.WORKSPACE}"
        echo ""
        
        // Check if folder exists
        def folderCheck = sh(
            script: "gsutil ls ${fullGcsPath} 2>&1",
            returnStdout: true
        ).trim()
        
        if (folderCheck.contains("BucketNotFoundException") || folderCheck.contains("NotFoundException")) {
            return [
                success: false,
                filesDownloaded: 0,
                buildNumber: null,
                error: "Vulnerability reports folder not found in GCS (vulnerability_reports_uat)"
            ]
        }
        
        echo "UAT folder exists, downloading files..."
        
        // Change to workspace directory to ensure files are downloaded there
        sh "cd ${env.WORKSPACE}"
        
        // Download each vulnerability count file (including DAST as acunetix)
        def scanTypes = ['snyk', 'checkov', 'prisma', 'sast', 'acunetix']  // DAST is named acunetix
        def downloadedFiles = 0
        def missingFiles = []
        def warnings = []
        
        scanTypes.each { scanType ->
            def sourceFile = "vulnerability_count_${scanType}.txt"
            def gcsFilePath = "${vulnerabilityReportsPath}/${sourceFile}"
            def fullFileUrl = "gs://${config.GCS_BUCKET}/${gcsFilePath}"
            def localFilePath = "${env.WORKSPACE}/${sourceFile}"
            
            echo "Downloading ${sourceFile} from UAT..."
            echo "  From: ${fullFileUrl}"
            echo "  To: ${localFilePath}"
            
            try {
                // Use explicit output path
                def downloadStatus = sh(
                    script: "gsutil cp ${fullFileUrl} ${localFilePath} 2>&1",
                    returnStatus: true
                )
                
                // Verify file was downloaded
                def fileCheck = sh(
                    script: "test -f ${localFilePath} && echo 'EXISTS' || echo 'MISSING'",
                    returnStdout: true
                ).trim()
                
                if (downloadStatus == 0 && fileCheck == 'EXISTS') {
                    def content = readFile(localFilePath).trim()
                    
                    if (content.isEmpty()) {
                        warnings << "${scanType}: Downloaded file is empty"
                        echo "  Downloaded but EMPTY"
                    } else if (content.contains("NO SCAN DATA AVAILABLE")) {
                        warnings << "${scanType}: Placeholder report detected"
                        echo "  Downloaded but is a PLACEHOLDER"
                        downloadedFiles++
                    } else if (content.contains("gcp_dev") && !content.contains("gcp_uat")) {
                        warnings << "${scanType}: Report is from gcp_dev, not gcp_uat"
                        echo "  Downloaded but from WRONG ENVIRONMENT (gcp_dev)"
                        downloadedFiles++
                    } else {
                        echo "  ✓ Successfully downloaded from UAT (${content.length()} bytes)"
                        downloadedFiles++
                    }
                } else {
                    missingFiles << sourceFile
                    echo "  ✗ FAILED to download from UAT (status: ${downloadStatus}, exists: ${fileCheck})"
                }
            } catch (Exception e) {
                missingFiles << sourceFile
                echo "  ✗ ERROR: ${e.getMessage()}"
            }
        }
        
        // List all downloaded files for verification
        echo ""
        echo "=== VERIFICATION: Files in workspace ==="
        sh """
            cd ${env.WORKSPACE}
            ls -lah vulnerability_count_*.txt 2>/dev/null || echo "No files found"
        """
        
        // Get build number from UAT metadata
        def buildNumber = null
        try {
            def metadataFile = sh(
                script: "gsutil ls ${fullGcsPath}build_*_metadata.txt 2>/dev/null | tail -1 || echo 'NONE'",
                returnStdout: true
            ).trim()
            
            if (!metadataFile.contains("NONE") && !metadataFile.isEmpty()) {
                def metadataContent = sh(
                    script: "gsutil cat ${metadataFile} 2>/dev/null || echo 'NONE'",
                    returnStdout: true
                ).trim()
                
                if (!metadataContent.contains("NONE")) {
                    def buildMatch = (metadataContent =~ /Build Number:\s*(\d+)/)
                    if (buildMatch) {
                        buildNumber = buildMatch[0][1]
                    }
                }
            }
        } catch (Exception e) {
            echo "Could not retrieve UAT metadata: ${e.getMessage()}"
        }
        
        return [
            success: downloadedFiles > 0,
            filesDownloaded: downloadedFiles,
            buildNumber: buildNumber,
            missingFiles: missingFiles,
            warnings: warnings
        ]
        
    } catch (Exception e) {
        echo "Exception in download function: ${e.getMessage()}"
        e.printStackTrace()
        return [
            success: false,
            filesDownloaded: 0,
            buildNumber: null,
            error: e.getMessage()
        ]
    }
}

// Generate validation report
def generateValidationReport(currentDate, config, thresholds, scanResults, overallPass, failureReasons, warnings, buildNumber, sourceEnv) {
    
    def totalCritical = scanResults.collect { k, v -> v.critical > 0 ? v.critical : 0 }.sum() ?: 0
    def totalHigh = scanResults.collect { k, v -> v.high > 0 ? v.high : 0 }.sum() ?: 0
    def totalMedium = scanResults.collect { k, v -> v.medium > 0 ? v.medium : 0 }.sum() ?: 0
    def totalLow = scanResults.collect { k, v -> v.low > 0 ? v.low : 0 }.sum() ?: 0
    def grandTotal = totalCritical + totalHigh + totalMedium + totalLow
    
    def report = """
================================================================================
            PRODUCTION TOLLGATE VALIDATION REPORT (FROM UAT)
================================================================================

REPORT DATE:         ${currentDate}
VALIDATION BUILD:    ${env.JOB_NAME} #${env.BUILD_NUMBER}
SOURCE ENVIRONMENT:  ${sourceEnv} (UAT)
SOURCE BUILD:        ${buildNumber}
TARGET ENVIRONMENT:  PRODUCTION
VALIDATION TYPE:     Pre-Production Security Gate

================================================================================
                      VULNERABILITY THRESHOLDS
================================================================================
TOOL        | CRITICAL | HIGH  | STATUS
------------|----------|-------|-------------------------------------------
Snyk        |    ${thresholds.snyk.critical.toString().padLeft(4)}  |  ${thresholds.snyk.high.toString().padLeft(4)} | ${scanResults.snyk?.passed ? 'PASS' : 'FAIL'}
Checkov     |    ${thresholds.checkov.critical.toString().padLeft(4)}  |  ${thresholds.checkov.high.toString().padLeft(4)} | ${scanResults.checkov?.passed ? 'PASS' : 'FAIL'}
Prisma      |    ${thresholds.prisma.critical.toString().padLeft(4)}  |  ${thresholds.prisma.high.toString().padLeft(4)} | ${scanResults.prisma?.passed ? 'PASS' : 'FAIL'}
SAST        |    ${thresholds.sast.critical.toString().padLeft(4)}  |  ${thresholds.sast.high.toString().padLeft(4)} | ${scanResults.sast?.passed ? 'PASS' : 'FAIL'}
DAST        |    ${thresholds.acunetix.critical.toString().padLeft(4)}  |  ${thresholds.acunetix.high.toString().padLeft(4)} | ${scanResults.acunetix?.passed ? 'PASS' : 'FAIL'}

================================================================================
                      VULNERABILITY COUNTS (FROM ${sourceEnv})
================================================================================
TOOL        | CRITICAL | HIGH  | MEDIUM | LOW   | TOTAL | VALIDATION
------------|----------|-------|--------|-------|-------|-------------------
${scanResults.collect { tool, data ->
    def toolLabel = tool.toUpperCase()
    if (tool == 'sast') {
        toolLabel = 'SAST (CX)'
    } else if (tool == 'acunetix') {
        toolLabel = 'DAST'  // Display as DAST even though file is acunetix
    }
    
    if (data.error) {
        "${toolLabel.padRight(11)} | ERROR    | ERROR | ERROR  | ERROR | ERROR | ${data.error.take(20)}"
    } else {
        def crit = data.critical.toString().padLeft(3)
        def high = data.high.toString().padLeft(3)
        def med = data.medium.toString().padLeft(3)
        def low = data.low.toString().padLeft(3)
        def tot = data.total.toString().padLeft(3)
        "${toolLabel.padRight(11)} |   ${crit}    |  ${high}  |  ${med}  |  ${low} |  ${tot} | ${data.passed ? 'PASS' : 'FAIL'}"
    }
}.join('\n')}
------------|----------|-------|--------|-------|-------|-------------------
TOTAL       |   ${totalCritical.toString().padLeft(3)}    |  ${totalHigh.toString().padLeft(3)}  |  ${totalMedium.toString().padLeft(3)}  |  ${totalLow.toString().padLeft(3)} |  ${grandTotal.toString().padLeft(3)} | ${overallPass ? 'PASS' : 'FAIL'}

================================================================================
                      VALIDATION RESULT
================================================================================

${overallPass ? '✓ PASSED - All vulnerability counts within production thresholds' : '✗ FAILED - Some vulnerability counts exceed production thresholds'}

${overallPass ? 'CODE APPROVED FOR PRODUCTION DEPLOYMENT' : 'CODE NOT APPROVED FOR PRODUCTION - REMEDIATION REQUIRED'}

${failureReasons.isEmpty() ? '' : 'FAILURES:\n' + failureReasons.collect { "  • ${it}" }.join('\n')}

${warnings.isEmpty() ? '' : '\nWARNINGS:\n' + warnings.collect { "  ⚠ ${it}" }.join('\n')}

================================================================================
                      CONFIGURATION
================================================================================
Environment Flow: UAT → PRODUCTION
Source Bucket: ${config.GCS_BUCKET}
Source Path: vulnerability_reports_uat/
Fail on vulnerabilities: ${config.FAIL_ON_VULNS}

Note: SAST (Checkmarx) does not have a Critical severity level.
      High is the highest severity for SAST scans.
      
Note: Reports are fetched from UAT environment (vulnerability_reports_uat folder)
      and validated against production-grade thresholds.

================================================================================
"""
    return report
}