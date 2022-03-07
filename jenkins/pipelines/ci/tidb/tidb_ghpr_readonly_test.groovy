specStr = "+refs/heads/*:refs/remotes/origin/*"
if (ghprbPullId != null && ghprbPullId != "") {
    specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
}

@NonCPS
boolean isMoreRecentOrEqual( String a, String b ) {
    if (a == b) {
        return true
    }

    [a,b]*.tokenize('.')*.collect { it as int }.with { u, v ->
       Integer result = [u,v].transpose().findResult{ x,y -> x <=> y ?: null } ?: u.size() <=> v.size()
       return (result == 1)
    } 
}

string trimPrefix = {
        it.startsWith('release-') ? it.minus('release-').split("-")[0] : it 
    }

def boolean isBranchMatched(List<String> branches, String targetBranch) {
    for (String item : branches) {
        if (targetBranch.startsWith(item)) {
            println "targetBranch=${targetBranch} matched in ${branches}"
            return true
        }
    }
    return false
}

def isNeedGo1160 = false
releaseBranchUseGo1160 = "release-5.1"

if (!isNeedGo1160) {
    isNeedGo1160 = isBranchMatched(["master", "hz-poc", "ft-data-inconsistency", "br-stream"], ghprbTargetBranch)
}
if (!isNeedGo1160 && ghprbTargetBranch.startsWith("release-")) {
    isNeedGo1160 = isMoreRecentOrEqual(trimPrefix(ghprbTargetBranch), trimPrefix(releaseBranchUseGo1160))
    if (isNeedGo1160) {
        println "targetBranch=${ghprbTargetBranch}  >= ${releaseBranchUseGo1160}"
    }
}

pod_go_docker_image = 'hub.pingcap.net/jenkins/centos7_golang-1.16:latest'
if (isNeedGo1160) {
    println "This build use go1.16"
} else {
    println "This build use go1.13"
    pod_go_docker_image = 'hub.pingcap.net/jenkins/centos7_golang-1.13:latest'
}

label = "tidb_ghpr_readonly_test-${BUILD_NUMBER}"
def run_with_pod(Closure body) {
    def cloud = "kubernetes"
    def namespace = "jenkins-tidb"
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${pod_go_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')], 
                    ),
                    containerTemplate(
                            name: 'ruby', alwaysPullImage: true,
                            image: "hub.pingcap.net/jenkins/centos7_ruby-2.6.3:latest", ttyEnabled: true,
                            resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                            command: '/bin/sh -c', args: 'cat', 
                    )
            ],
            volumes: [
                            nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                                    serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: false),
                            emptyDirVolume(mountPath: '/tmp', memory: false),
                            emptyDirVolume(mountPath: '/home/jenkins', memory: false)
                    ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            timeout(time: 60, unit: 'MINUTES') {
               body() 
            }
        }
    }
}



run_with_pod {
    def ws = pwd()

    stage("Checkout") {
        container("golang") {
            sh "whoami && go version"
        }
        // update code
        dir("/home/jenkins/agent/code-archive") {
            // delete to clean workspace in case of agent pod reused lead to conflict.
            deleteDir()
            // copy code from nfs cache
            container("golang") {
                if(fileExists("/home/jenkins/agent/ci-cached-code-daily/src-tidb.tar.gz")){
                    timeout(5) {
                        sh """
                            cp -R /home/jenkins/agent/ci-cached-code-daily/src-tidb.tar.gz*  ./
                            mkdir -p ${ws}/go/src/github.com/pingcap/tidb
                            tar -xzf src-tidb.tar.gz -C ${ws}/go/src/github.com/pingcap/tidb --strip-components=1
                        """
                    }
                }
            }
            dir("${ws}/go/src/github.com/pingcap/tidb") {
                try {
                    checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 10]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb.git']]]
                }   catch (info) {
                        retry(2) {
                            echo "checkout failed, retry.."
                            sleep 5
                            if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                deleteDir()
                            }
                            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 10]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb.git']]]
                        }
                }
                container("golang") {
                    def tidb_path = "${ws}/go/src/github.com/pingcap/tidb"
                    timeout(5) {
                        sh """
                        git checkout -f ${ghprbActualCommit}
                        """
                    }
                }
            }
        }
    }

    stage("Test") {
        def tidb_path = "${ws}/go/src/github.com/pingcap/tidb"
        dir("go/src/github.com/pingcap/tidb") {
            container("golang") {
                def tikv_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${ghprbTargetBranch}/sha1"
                def tikv_sha1 = sh(returnStdout: true, script: "curl ${tikv_refs}").trim()
                tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"

                def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${ghprbTargetBranch}/sha1"
                def pd_sha1 = sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
                try {
                    sh """
                    make
                    curl ${tikv_url} | tar xz
                    curl ${pd_url} | tar xz bin

                    bin/pd-server -name=pd --data-dir=pd --client-urls=http://127.0.0.1:2379 &> pd.log &
                    sleep 20
                    bin/tikv-server --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 -f tikv.log &
                    sleep 20

                    bin/tidb-server -P 4001 -host 127.0.0.1 -store tikv -path 127.0.0.1:2379 -status 10081 &> tidb1.log &
                    bin/tidb-server -P 4002 -host 127.0.0.1 -store tikv -path 127.0.0.1:2379 -status 10082 &> tidb2.log &
                    sleep 20
                    
                    cd tests/readonlytest
                    go mod tidy
                    go test
                    """
                }catch (Exception e) {
                    sh "cat ${ws}/go/src/github.com/pingcap/tidb/tidb1.log || true"
                    sh "cat ${ws}/go/src/github.com/pingcap/tidb/tidb2.log || true"
                    throw e
                }finally {
                    sh """
                    set +e
                    killall -9 -r tidb-server
                    killall -9 -r tikv-server
                    killall -9 -r pd-server
                    set -e
                    """
                }
            }
        }
    }
}