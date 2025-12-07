import java.text.SimpleDateFormat

def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def GCS_BUCKET = config.GCS_BUCKET ?: 'bkt-modernization-dev-securityreports'  // FIXED: Use dev bucket
    def BUCKET_PROJECT_NAME = config.BUCKET_PROJECT_NAME ?: 'modernization-dev-832770'  // FIXED: Use dev project
    def GCP_KEY_FILE = config.GCP_KEY_FILE ?: '/home/jenkins/agent/key/key/mpaas-prod-all-access.json'
    def BRANCH_NAME = config.BRANCH_NAME ?: env.BRANCH_NAME ?: 'unknown'

    try {
        def podLabel = "gcs-upload-${UUID.randomUUID().toString()}"
        
        podTemplate(
            label: podLabel,
            nodeSelector: 'cloud.google.com/gke-nodepool=spot-node-pool-1',
            containers: [
                containerTemplate(
                    name: 'jnlp', 
                    image: 'asia-south1-docker.pkg.dev/common-infra-services/jenkins-agent-updated/agent:latest'
                )
            ],
            volumes: [
                hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock'),
                persistentVolumeClaim(claimName: 'jenkinsagentpvc', mountPath: '/home/jenkins/agent', readOnly: false)
            ]
        ) {
            node(podLabel) {
                container('jnlp') {
                    stage('Upload Vulnerability Reports to GCS') {
                        echo "=========================================================================="
                        echo "           UPLOADING VULNERABILITY REPORTS TO GCS (UAT)"
                        echo "=========================================================================="
                        echo "Running in pod: ${env.NODE_NAME}"
                        echo "Workspace: ${env.WORKSPACE}"
                        echo "Environment: UAT"
                        
                        // CRITICAL: The vulnerability files should already be in the workspace from previous stages
                        echo ""
                        echo "=== CHECKING WORKSPACE FOR VULNERABILITY FILES ==="
                        sh """
                            echo "Current directory: \$(pwd)"
                            echo ""
                            echo "Looking for vulnerability_count_*.txt files:"
                            find . -name "vulnerability_count_*.txt" -type f 2>/dev/null || echo "No vulnerability_count files found"
                            echo ""
                            echo "All text files in workspace:"
                            find . -name "*.txt" -type f 2>/dev/null | head -20 || echo "No text files found"
                            echo ""
                            echo "=== SPECIFICALLY CHECKING FOR REQUIRED FILES ==="
                            ls -la vulnerability_count_sast.txt 2>/dev/null || echo "vulnerability_count_sast.txt NOT FOUND"
                            ls -la vulnerability_count_acunetix.txt 2>/dev/null || echo "vulnerability_count_acunetix.txt NOT FOUND"
                        """
                        
                        // Verify files exist in workspace
                        def filesExist = sh(
                            script: 'ls vulnerability_count_*.txt 2>/dev/null | wc -l',
                            returnStdout: true
                        ).trim().toInteger()
                        
                        if (filesExist == 0) {
                            echo "=========================================================================="
                            echo "ERROR: NO VULNERABILITY_COUNT FILES FOUND IN WORKSPACE"
                            echo "=========================================================================="
                            echo "This stage must run on the SAME node as your security scan stages."
                            echo "Files expected: vulnerability_count_snyk.txt, vulnerability_count_checkov.txt, vulnerability_count_prisma.txt, vulnerability_count_sast.txt, vulnerability_count_acunetix.txt"
                            currentBuild.result = 'UNSTABLE'
                            return
                        } else {
                            echo "✓ Found ${filesExist} vulnerability files in workspace"
                        }
                        
                        echo ""
                        echo "=== IDENTIFYING VULNERABILITY COUNT FILES ==="
                        def vulnerabilityFiles = []
                        def filePatterns = [
                            'vulnerability_count_snyk.txt',
                            'vulnerability_count_checkov.txt', 
                            'vulnerability_count_prisma.txt',
                            'vulnerability_count_sast.txt',
                            'vulnerability_count_acunetix.txt'  // DAST file for UAT
                        ]
                        
                        filePatterns.each { file ->
                            if (fileExists(file)) {
                                vulnerabilityFiles << file
                                echo "✓ Found: ${file}"
                            } else {
                                echo "✗ Not found: ${file}"
                            }
                        }
                        
                        // Also include any other vulnerability_count files that might exist
                        def additionalFiles = findFiles(glob: 'vulnerability_count_*.txt')
                        additionalFiles.each { file ->
                            if (!vulnerabilityFiles.contains(file.name)) {
                                vulnerabilityFiles << file.name
                                echo "✓ Found additional: ${file.name}"
                            }
                        }
                        
                        if (vulnerabilityFiles.isEmpty()) {
                            echo "=========================================================================="
                            echo "ERROR: NO VULNERABILITY COUNT FILES FOUND"
                            echo "=========================================================================="
                            echo "Expected files in workspace:"
                            filePatterns.each { echo "  - ${it}" }
                            echo ""
                            echo "Please ensure the Archive stage created these files BEFORE this stage runs"
                            currentBuild.result = 'UNSTABLE'
                            return
                        }
                        
                        echo ""
                        echo "=== DISPLAYING FILE CONTENTS FOR VERIFICATION ==="
                        vulnerabilityFiles.each { file ->
                            echo "=========================================================================="
                            echo "FILE: ${file}"
                            echo "=========================================================================="
                            sh "cat ${file} 2>/dev/null || echo 'Could not read file content'"
                            echo ""
                        }
                        
                        // Prepare GCS upload path - UAT specific folder structure
                        def shortEnv = BRANCH_NAME.tokenize('_')[-1]
                        def jobParts = env.JOB_NAME.split('/')
                        def JOB_NAME_CLEAN = jobParts[0..-2].join('/')
                        def buildNumber = env.BUILD_NUMBER
                        def timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
                        
                        // UAT GCS Path Structure in the same dev bucket
                        def baseGcsPath = "DevSecOps/${JOB_NAME_CLEAN}"
                        def vulnerabilityReportsPath = "${baseGcsPath}/vulnerability_reports_uat"  // UAT specific folder
                        
                        echo ""
                        echo "=== GCS UPLOAD CONFIGURATION (UAT) ==="
                        echo "Bucket: ${GCS_BUCKET}"
                        echo "Project: ${BUCKET_PROJECT_NAME}"
                        echo "Base Path: ${baseGcsPath}"
                        echo "Vulnerability Reports Path: ${vulnerabilityReportsPath}"
                        echo "Environment: ${shortEnv} (UAT)"
                        echo "Build Number: ${buildNumber}"
                        echo "Files to upload: ${vulnerabilityFiles.size()}"
                        echo "Files: ${vulnerabilityFiles.join(', ')}"
                        
                        // Setup GCP authentication
                        echo ""
                        echo "=== SETTING UP GCP AUTHENTICATION ==="
                        sh """
                            # Remove previous CLI if exists
                            sudo apt-get remove -y google-cloud-cli 2>/dev/null || true
                            sudo rm -f /etc/apt/sources.list.d/kubernetes.list
                            sudo apt-get update
                            
                            # Add Google Cloud SDK repository
                            sudo mkdir -p /etc/apt/keyrings
                            curl -fsSL https://packages.cloud.google.com/apt/doc/apt-key.gpg | sudo tee /etc/apt/keyrings/cloud.google.gpg > /dev/null
                            sudo chmod 644 /etc/apt/keyrings/cloud.google.gpg
                            
                            echo 'deb [signed-by=/etc/apt/keyrings/cloud.google.gpg] http://packages.cloud.google.com/apt cloud-sdk main' | sudo tee /etc/apt/sources.list.d/google-cloud-sdk.list > /dev/null
                            
                            sudo apt-get update && sudo apt-get install -y google-cloud-sdk
                            
                            # Authenticate
                            gcloud auth activate-service-account --key-file=${GCP_KEY_FILE}
                            gcloud config set project ${BUCKET_PROJECT_NAME}
                            
                            echo "GCP authentication successful"
                        """
                        
                        // Verify bucket exists and create folder structure if needed
                        echo ""
                        echo "=== VERIFYING/CREATING GCS FOLDER STRUCTURE ==="
                        sh """
                            # Check if bucket exists
                            if gsutil ls gs://${GCS_BUCKET} >/dev/null 2>&1; then
                                echo "✓ Bucket exists: ${GCS_BUCKET}"
                            else
                                echo "✗ ERROR: Bucket does not exist: ${GCS_BUCKET}"
                                exit 1
                            fi
                            
                            # Create folder structure by uploading a placeholder (GCS creates folders when you upload)
                            echo "Creating folder structure if not exists..."
                            
                            # Create vulnerability_reports_uat folder marker
                            echo "Folder structure marker" > /tmp/folder_marker.txt
                            gsutil cp /tmp/folder_marker.txt gs://${GCS_BUCKET}/${vulnerabilityReportsPath}/.folder_marker 2>/dev/null || true
                            
                            # Verify folder structure
                            echo ""
                            echo "Verifying folder structure:"
                            gsutil ls gs://${GCS_BUCKET}/${baseGcsPath}/ 2>&1 || echo "Base path will be created with first upload"
                            gsutil ls gs://${GCS_BUCKET}/${vulnerabilityReportsPath}/ 2>&1 || echo "UAT folder will be created with first upload"
                        """
                        
                        // Upload vulnerability count files
                        echo ""
                        echo "=== UPLOADING VULNERABILITY COUNT FILES TO GCS (UAT) ==="
                        def uploadedFiles = []
                        def failedFiles = []
                        
                        vulnerabilityFiles.each { file ->
                            try {
                                def gcsFilePath = "${vulnerabilityReportsPath}/${file}"
                                def fullGcsUrl = "gs://${GCS_BUCKET}/${gcsFilePath}"
                                
                                echo "Uploading: ${file}"
                                echo "  To: ${fullGcsUrl}"
                                
                                def uploadResult = sh(
                                    script: "gsutil cp ${file} ${fullGcsUrl} 2>&1",
                                    returnStatus: true
                                )
                                
                                if (uploadResult == 0) {
                                    // Verify upload
                                    def verifyResult = sh(
                                        script: "gsutil ls ${fullGcsUrl} 2>&1",
                                        returnStdout: true
                                    ).trim()
                                    
                                    if (verifyResult.contains(file) || verifyResult.contains(gcsFilePath)) {
                                        echo "  ✓ Successfully uploaded and verified"
                                        uploadedFiles << file
                                    } else {
                                        echo "  ⚠ Upload completed but verification failed"
                                        uploadedFiles << file  // Still count as uploaded
                                    }
                                } else {
                                    echo "  ✗ Upload failed with status: ${uploadResult}"
                                    failedFiles << file
                                }
                                
                            } catch (Exception e) {
                                echo "  ✗ Failed to upload ${file}: ${e.message}"
                                failedFiles << file
                            }
                        }
                        
                        // Upload full report files to tool-specific folders - INCLUDING DAST
                        echo ""
                        echo "=== UPLOADING FULL SCAN REPORTS ==="
                        def reportFiles = [
                            [file: "Snyk_Report.json", folder: "snyk", gcsName: "snyk_report_${shortEnv}_${buildNumber}.json"],
                            [file: "Snyk_Report.html", folder: "snyk", gcsName: "snyk_report_${shortEnv}_${buildNumber}.html"],
                            [file: "Snyk_Report.pdf", folder: "snyk", gcsName: "snyk_report_${shortEnv}_${buildNumber}.pdf"],
                            [file: "checkov_report.json", folder: "checkov", gcsName: "checkov_report_${shortEnv}_${buildNumber}.json"],
                            [file: "prisma_report.json", folder: "prisma", gcsName: "prisma_report_${shortEnv}_${buildNumber}.json"],
                            [file: "checkmarx_report.csv", folder: "checkmarx", gcsName: "checkmarx_report_${shortEnv}_${buildNumber}.csv"],
                            [file: "checkmarx_report.pdf", folder: "checkmarx", gcsName: "checkmarx_report_${shortEnv}_${buildNumber}.pdf"],
                            [file: "SAST_Report.pdf", folder: "checkmarx", gcsName: "sast_report_${shortEnv}_${buildNumber}.pdf"],
                            [file: "SAST_Report.xml", folder: "checkmarx", gcsName: "sast_report_${shortEnv}_${buildNumber}.xml"],
                            [file: "acunetix_report.json", folder: "acunetix", gcsName: "acunetix_report_${shortEnv}_${buildNumber}.json"],
                            [file: "acunetix_report.pdf", folder: "acunetix", gcsName: "acunetix_report_${shortEnv}_${buildNumber}.pdf"],
                            [file: "DAST_Report.pdf", folder: "acunetix", gcsName: "dast_report_${shortEnv}_${buildNumber}.pdf"],
                            [file: "DAST_Report.xml", folder: "acunetix", gcsName: "dast_report_${shortEnv}_${buildNumber}.xml"],
                            [file: "combined_security_report.txt", folder: "summary", gcsName: "combined_security_report_${shortEnv}_${buildNumber}.txt"],
                            [file: "all_security_reports.zip", folder: "summary", gcsName: "all_security_reports_${shortEnv}_${buildNumber}.zip"]
                        ]
                        
                        reportFiles.each { report ->
                            if (fileExists(report.file)) {
                                try {
                                    def gcsPath = "${baseGcsPath}/${report.folder}/${report.gcsName}"
                                    echo "Uploading ${report.file} → gs://${GCS_BUCKET}/${gcsPath}"
                                    def uploadStatus = sh(
                                        script: "gsutil cp ${report.file} gs://${GCS_BUCKET}/${gcsPath} 2>&1",
                                        returnStatus: true
                                    )
                                    if (uploadStatus == 0) {
                                        echo "  ✓ Uploaded"
                                    } else {
                                        echo "  ✗ Failed with status: ${uploadStatus}"
                                    }
                                } catch (Exception e) {
                                    echo "  ✗ Failed: ${e.message}"
                                }
                            } else {
                                echo "  ⊘ File not found: ${report.file}"
                            }
                        }
                        
                        // Create metadata file
                        echo ""
                        echo "=== CREATING BUILD METADATA (UAT) ==="
                        def metadataContent = """
========================================
  BUILD METADATA (UAT)
========================================
Job Name: ${env.JOB_NAME}
Build Number: ${buildNumber}
Branch: ${BRANCH_NAME}
Environment: ${shortEnv} (UAT)
Upload Timestamp: ${timestamp}
GCS Base Path: ${baseGcsPath}
Vulnerability Reports Path: ${vulnerabilityReportsPath}

VULNERABILITY FILES UPLOADED:
${uploadedFiles.collect { "  ✓ ${it}" }.join('\n')}

${failedFiles ? 'FAILED UPLOADS:\n' + failedFiles.collect { "  ✗ ${it}" }.join('\n') : ''}

NOTE: These reports will be used for Production tollgate validation
========================================
"""
                        writeFile file: "build_metadata_uat.txt", text: metadataContent
                        def metadataUpload = sh(
                            script: "gsutil cp build_metadata_uat.txt gs://${GCS_BUCKET}/${vulnerabilityReportsPath}/build_${buildNumber}_metadata.txt 2>&1",
                            returnStatus: true
                        )
                        if (metadataUpload == 0) {
                            echo "  ✓ Metadata uploaded"
                        } else {
                            echo "  ✗ Metadata upload failed"
                        }
                        
                        // Verify all uploads
                        echo ""
                        echo "=== VERIFYING UPLOADS IN GCS (UAT) ==="
                        sh """
                            echo "Listing vulnerability_reports_uat folder:"
                            gsutil ls gs://${GCS_BUCKET}/${vulnerabilityReportsPath}/ 2>&1 || echo "Could not list folder"
                            echo ""
                            echo "Listing base folder structure:"
                            gsutil ls gs://${GCS_BUCKET}/${baseGcsPath}/ 2>&1 || echo "Could not list base folder"
                        """
                        
                        // Create summary
                        def uploadSummary = """
========================================================================
              GCS UPLOAD SUMMARY (UAT) - ${uploadedFiles.size() == vulnerabilityFiles.size() ? 'SUCCESS' : 'PARTIAL'}
========================================================================
Bucket:          ${GCS_BUCKET}
Project Path:    ${baseGcsPath}
Environment:     ${shortEnv} (UAT)
Build Number:    ${buildNumber}
Job:             ${env.JOB_NAME}
Branch:          ${BRANCH_NAME}
Timestamp:       ${timestamp}

UPLOADED VULNERABILITY FILES:  ${uploadedFiles.size()} of ${vulnerabilityFiles.size()}
${uploadedFiles.collect { "  ✓ ${it}" }.join('\n')}

${failedFiles ? 'FAILED UPLOADS:  ' + failedFiles.size() + '\n' + failedFiles.collect { "  ✗ ${it}" }.join('\n') : ''}

GCS STRUCTURE (UAT):
gs://${GCS_BUCKET}/${baseGcsPath}/
├── vulnerability_reports_uat/
│   ├── vulnerability_count_snyk.txt
│   ├── vulnerability_count_checkov.txt
│   ├── vulnerability_count_prisma.txt
│   ├── vulnerability_count_sast.txt
│   ├── vulnerability_count_acunetix.txt
│   └── build_${buildNumber}_metadata.txt
├── snyk/
├── checkov/
├── prisma/
├── checkmarx/
├── acunetix/
└── summary/

GCS URL: https://console.cloud.google.com/storage/browser/${GCS_BUCKET}/${baseGcsPath}

NOTE: These reports are in vulnerability_reports_uat/ folder
      Production tollgate validation will fetch from this folder
========================================================================
"""
                        echo uploadSummary
                        
                        // Save summary as artifact
                        writeFile file: 'gcs_upload_summary_uat.txt', text: uploadSummary
                        archiveArtifacts artifacts: 'gcs_upload_summary_uat.txt', allowEmptyArchive: true
                        
                        if (failedFiles.size() > 0) {
                            echo "⚠ Some files failed to upload - build marked as UNSTABLE"
                            currentBuild.result = 'UNSTABLE'
                        } else {
                            echo "✓ All vulnerability reports successfully uploaded to GCS (UAT)"
                            echo "✓ Folder structure: DevSecOps/${JOB_NAME_CLEAN}/vulnerability_reports_uat/"
                            echo "✓ SAST report (vulnerability_count_sast.txt) included"
                            echo "✓ DAST report (vulnerability_count_acunetix.txt) included"
                            echo "✓ Ready for Production tollgate validation"
                        }
                    }
                }
            }
        }
    } catch (Exception e) {
        echo "=========================================================================="
        echo "ERROR: Failed to upload vulnerability reports to GCS (UAT)"
        echo "=========================================================================="
        echo "Error message: ${e.message}"
        e.printStackTrace()
        currentBuild.result = 'UNSTABLE'
    }
}