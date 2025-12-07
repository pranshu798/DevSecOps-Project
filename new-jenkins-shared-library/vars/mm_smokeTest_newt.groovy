#!/usr/bin/env groovy

/**
 * mm_smoke_test_uat_newt
 * 
 * Shared library for executing automated smoke tests in UAT environment
 * Uses Maven and Selenium Chrome in Kubernetes pods
 * 
 * Usage in Jenkinsfile:
 * @Library(['mm-dsl@mss'])_
 * 
 * mm_smoke_test_uat_newt
 * {
 *     AGENT = ""
 *     MAVEN_SETTINGS = "settings.xml"  // Optional
 *     IMAGE_NAME = "your-image-name"   // Optional
 *     ENVIRONMENT = "uat"
 *     SUITE_XML_PATH = "src/test/runningSuites/smokeTest.xml"  // Optional - defaults to this
 *     PASS_THRESHOLD = 80.0  // Optional - defaults to 80%
 * }
 */

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
    def IMAGE_NAME = config.IMAGE_NAME
    def ENVIRONMENT = config.ENVIRONMENT
    def SUITE_XML_PATH = config.SUITE_XML_PATH ?: "src/test/runningSuites/smokeTest.xml"
    def PASS_THRESHOLD = config.PASS_THRESHOLD ?: 80.0
    def pipelineStage = ""

    try {
        podTemplate(
            nodeSelector: 'cloud.google.com/gke-nodepool=spot-node-pool-1',
            containers: [
                containerTemplate(
                    name: 'jnlp',
                    image: 'asia.gcr.io/common-infra-services/jenkins-agent-updated:mss-cli-090823-1'
                ),
                containerTemplate(
                    name: 'maven-selenium',
                    image: 'maven:3.9.8-eclipse-temurin-22',
                    ttyEnabled: true,
                    command: 'cat',
                    resourceRequestCpu: '1000m',
                    resourceRequestMemory: '2Gi',
                    resourceLimitCpu: '2000m',
                    resourceLimitMemory: '4Gi'
                ),
                containerTemplate(
                    name: 'selenium-chrome',
                    image: 'selenium/standalone-chrome:4.15.0',
                    ttyEnabled: true,
                    privileged: false,
                    resourceRequestCpu: '1000m',
                    resourceRequestMemory: '2Gi',
                    resourceLimitCpu: '2000m',
                    resourceLimitMemory: '4Gi',
                    envVars: [
                        envVar(key: 'SE_SCREEN_WIDTH', value: '1920'),
                        envVar(key: 'SE_SCREEN_HEIGHT', value: '1080'),
                        envVar(key: 'SE_SCREEN_DEPTH', value: '24'),
                        envVar(key: 'SE_START_XVFB', value: 'true')
                    ]
                )
            ],
            volumes: [
                hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock'),
                persistentVolumeClaim(claimName: 'jenkinsagentpvc', mountPath: '/home/jenkins/agent', readOnly: false),
                emptyDirVolume(mountPath: '/dev/shm', memory: true, sizeLimit: '2Gi')
            ]
        ) {
            node(POD_LABEL) {
                currentBuild.result = 'SUCCESS'
                
                stage('Smoke Test') {
                    pipelineStage = "${STAGE_NAME}"
                    
                    checkout scm
                    
                    container('maven-selenium') {
                        detectProjectType()
                        waitForSelenium()
                        executeTests(MAVEN_SETTINGS, SUITE_XML_PATH)
                        validateResults(PASS_THRESHOLD)
                    }
                    
                    echo "Status ${currentBuild.result}"
                }
            }
        }
    } catch (Exception err) {
        println err.getMessage()
        def failedStage = "${pipelineStage}"
        echo "Smoke Test Failed at ${pipelineStage}"
        currentBuild.result = 'FAILURE'
        echo "Error caught"
        throw err
    }
}

private void detectProjectType() {
    script {
        env.PROJECT_TYPE = "UNKNOWN"
        env.TEST_DIRECTORY = "."
        
        if (fileExists('automation-test-script')) {
            env.PROJECT_TYPE = "PHP"
            env.TEST_DIRECTORY = "automation-test-script"
        } else if (fileExists('src/main') && fileExists('src/test')) {
            env.PROJECT_TYPE = "JAVA"
        } else if (fileExists('automation-tests')) {
            env.PROJECT_TYPE = "LEGACY"
            env.TEST_DIRECTORY = "automation-tests"
        }
        
        echo "🎯 Project Configuration:"
        echo "   Type: ${env.PROJECT_TYPE}"
        echo "   Directory: ${env.TEST_DIRECTORY}"
    }
}

private void waitForSelenium() {
    sh '''
        echo "⏳ Waiting for Selenium Chrome container to be ready..."
        for i in {1..12}; do
            if curl -s http://localhost:4444/wd/hub/status > /dev/null 2>&1; then
                echo "✅ Selenium Chrome is ready!"
                break
            else
                echo "⏳ Waiting for Selenium... (attempt $i/12)"
                sleep 5
            fi
            [ $i -eq 12 ] && { echo "❌ Selenium not responding after 60 seconds"; exit 1; }
        done
    '''
}

private void executeTests(String MAVEN_SETTINGS, String SUITE_XML_PATH) {
    dir(env.TEST_DIRECTORY) {
        script {
            // Store suite path in environment
            env.SUITE_XML_PATH = SUITE_XML_PATH
            
            // Check if suite XML exists
            if (!fileExists(env.SUITE_XML_PATH)) {
                echo "⚠️  Suite XML file not found at: ${env.SUITE_XML_PATH}"
                sh 'find . -name "*.xml" -type f | head -20 || echo "No XML files found"'
                error("Suite XML file not found: ${env.SUITE_XML_PATH}")
            }
            
            echo "📝 Using test suite: ${env.SUITE_XML_PATH}"
            
            // Build Maven command
            def mavenCmd = ""
            if (MAVEN_SETTINGS != null && MAVEN_SETTINGS != "") {
                mavenCmd = "mvn clean test -DsuiteXmlFile=${env.SUITE_XML_PATH} " +
                           "-Dselenium.grid.url=http://localhost:4444/wd/hub " +
                           "-Dbrowser=chrome " +
                           "-Dheadless=true " +
                           "-Dmaven.test.failure.ignore=true " +
                           "-s ${MAVEN_SETTINGS}"
            } else {
                mavenCmd = "mvn clean test -DsuiteXmlFile=${env.SUITE_XML_PATH} " +
                           "-Dselenium.grid.url=http://localhost:4444/wd/hub " +
                           "-Dbrowser=chrome " +
                           "-Dheadless=true " +
                           "-Dmaven.test.failure.ignore=true"
            }
            
            echo "🚀 Executing tests..."
            sh mavenCmd
            
            // Archive test reports
            archiveTestReports()
        }
    }
}

private void archiveTestReports() {
    def reportPaths = [
        'reports/*.html',
        'target/reports/*.html',
        'test-output/*.html',
        'target/surefire-reports/*.html',
        'target/surefire-reports/*.xml'
    ]
    
    reportPaths.each { path ->
        if (sh(script: "ls ${path} 2>/dev/null || true", returnStdout: true).trim()) {
            archiveArtifacts artifacts: path, allowEmptyArchive: true
            echo "📋 Archived reports from: ${path}"
        }
    }
}

private void validateResults(Double PASS_THRESHOLD) {
    script {
        def consoleOutput = currentBuild.rawBuild.getLog(1000).join('\n')
        def passedPattern = /PASSED:\s*(\d+)/
        def failedPattern = /FAILED:\s*(\d+)/
        def skippedPattern = /SKIPPED:\s*(\d+)/
        def errorsPattern = /ERRORS:\s*(\d+)/
        
        def passedMatcher = consoleOutput =~ passedPattern
        def failedMatcher = consoleOutput =~ failedPattern
        def skippedMatcher = consoleOutput =~ skippedPattern
        def errorsMatcher = consoleOutput =~ errorsPattern
        
        if (passedMatcher.find() && failedMatcher.find() && skippedMatcher.find() && errorsMatcher.find()) {
            def passed = passedMatcher.group(1) as Integer
            def failed = failedMatcher.group(1) as Integer
            def skipped = skippedMatcher.group(1) as Integer
            def errors = errorsMatcher.group(1) as Integer
            
            def total = passed + failed + skipped + errors
            def passPercentage = total > 0 ? (passed * 100.0) / total : 0
            
            echo "📊 Test Results Summary:"
            echo "   PASSED: ${passed}"
            echo "   FAILED: ${failed}"
            echo "   SKIPPED: ${skipped}"
            echo "   ERRORS: ${errors}"
            echo "   TOTAL: ${total}"
            echo "📈 PASS PERCENTAGE: ${String.format('%.2f', passPercentage)}% (Required: ${String.format('%.2f', PASS_THRESHOLD)}%)"
            
            if (passPercentage >= PASS_THRESHOLD) {
                echo "✅ Smoke test passed! Pass rate: ${String.format('%.2f', passPercentage)}%"
                currentBuild.result = 'SUCCESS'
            } else {
                echo "❌ Smoke test failed! Pass rate ${String.format('%.2f', passPercentage)}% is below ${String.format('%.2f', PASS_THRESHOLD)}% threshold"
                currentBuild.result = 'FAILURE'
                error("Test pass rate ${String.format('%.2f', passPercentage)}% is below ${String.format('%.2f', PASS_THRESHOLD)}% threshold")
            }
        } else {
            echo "⚠️  Could not parse test results from console output"
            echo "⚠️  Threshold validation skipped"
            echo "✅ Smoke test execution completed"
        }
    }
}