
pipeline {
    agent any

    parameters {
        choice(
                name: 'CLUSTER',
                choices: ['order-app', 'payment-app-cluster'],
                description: 'Select DigitalOcean Kubernetes cluster'
        )

        choice(
                name: 'APP_NAME',
                choices: ['payment-app', 'order-app'],
                description: 'Application to roll back'
        )

        string(
                name: 'IMAGE_VERSION',
                defaultValue: '1.0.4',
                description: 'Existing image version to restore'
        )

        string(
                name: 'NAMESPACE',
                defaultValue: 'payment-app',
                description: 'Kubernetes namespace containing the Deployment'
        )
    }

    environment {
        REGISTRY = '104.248.121.80:5000'
        DO_TOKEN = credentials('do-token')
    }

    stages {
        stage('Validate Parameters') {
            steps {
                script {
                    def versionParts = params.IMAGE_VERSION.split('\\.', -1)

                    if (versionParts.length != 3 ||
                            versionParts.any { part ->
                                part.isEmpty() ||
                                        !part.every { ch -> ch >= '0' && ch <= '9' }
                            }) {
                        error('Invalid version. Use format x.y.z, e.g. 1.0.4')
                    }

                    if (!(params.APP_NAME in ['payment-app', 'order-app'])) {
                        error('Unsupported application.')
                    }

                    if (!(params.CLUSTER in ['order-app', 'payment-app-cluster'])) {
                        error('Unsupported cluster.')
                    }

                    if (!(params.NAMESPACE == 'payment-app' ||
                            params.NAMESPACE == 'order-app')) {
                        error('Unsupported namespace.')
                    }

                    def expected = [
                            'payment-app': [
                                    cluster: 'payment-app-cluster',
                                    namespace: 'payment-app'
                            ],
                            'order-app': [
                                    cluster: 'order-app',
                                    namespace: 'order-app'
                            ]
                    ]

                    if (expected[params.APP_NAME].cluster != params.CLUSTER) {
                        error("Application ${params.APP_NAME} does not match cluster ${params.CLUSTER}.")
                    }

                    if (expected[params.APP_NAME].namespace != params.NAMESPACE) {
                        error("Application ${params.APP_NAME} does not match namespace ${params.NAMESPACE}.")
                    }
                }
            }
        }

        stage('Prepare Tools') {
            steps {
                sh '''
                    set -eu

                    mkdir -p "$WORKSPACE/bin" "$WORKSPACE/.kube"

                    rm -f "$WORKSPACE/bin/kubectl"
                    rm -f "$WORKSPACE/bin/doctl"
                    rm -f "$WORKSPACE/doctl.tar.gz"

                    echo "===== Downloading kubectl ====="

                    KUBECTL_VERSION=$(curl -fsSL \
                        https://dl.k8s.io/release/stable.txt)

                    curl -fsSL \
                        "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/amd64/kubectl" \
                        -o "$WORKSPACE/bin/kubectl"

                    chmod 755 "$WORKSPACE/bin/kubectl"

                    echo "===== Downloading doctl ====="

                    curl -fsSL \
                        "https://github.com/digitalocean/doctl/releases/download/v1.117.0/doctl-1.117.0-linux-amd64.tar.gz" \
                        -o "$WORKSPACE/doctl.tar.gz"

                    tar -xzf "$WORKSPACE/doctl.tar.gz" \
                        -C "$WORKSPACE/bin"

                    chmod 755 "$WORKSPACE/bin/doctl"

                    export PATH="$WORKSPACE/bin:$PATH"

                    kubectl version --client
                    doctl version
                '''
            }
        }

        stage('Configure Cluster Access') {
            steps {
                sh '''
                    set -eu

                    export PATH="$WORKSPACE/bin:$PATH"
                    export KUBECONFIG="$WORKSPACE/.kube/config"

                    mkdir -p "$WORKSPACE/.kube"
                    chmod 700 "$WORKSPACE/.kube"

                    doctl auth init --access-token "$DO_TOKEN"
                    doctl kubernetes cluster kubeconfig save "$CLUSTER"

                    echo "===== Kubernetes context ====="
                    kubectl config current-context

                    echo "===== Kubernetes nodes ====="
                    kubectl get nodes
                '''
            }
        }

        stage('Verify Deployment') {
            steps {
                sh '''
                    set -eu

                    export PATH="$WORKSPACE/bin:$PATH"
                    export KUBECONFIG="$WORKSPACE/.kube/config"

                    kubectl get deployment "$APP_NAME" -n "$NAMESPACE"

                    echo "===== Container names ====="
                    kubectl get deployment "$APP_NAME" \
                        -n "$NAMESPACE" \
                        -o jsonpath='{.spec.template.spec.containers[*].name}'
                    echo
                '''
            }
        }

        stage('Verify Registry Image') {
            steps {
                sh '''
                    set -eu

                    IMAGE_URL="https://$REGISTRY/v2/$APP_NAME/manifests/$IMAGE_VERSION"

                    echo "Checking image: $REGISTRY/$APP_NAME:$IMAGE_VERSION"

                    HTTP_STATUS=$(curl \
                        --silent \
                        --show-error \
                        --output "$WORKSPACE/registry-manifest.json" \
                        --write-out "%{http_code}" \
                        -H "Accept: application/vnd.docker.distribution.manifest.v2+json, application/vnd.oci.image.manifest.v1+json" \
                        "$IMAGE_URL")

                    echo "Registry HTTP status: $HTTP_STATUS"

                    if [ "$HTTP_STATUS" != "200" ]; then
                        echo "ERROR: Registry did not confirm the image."
                        cat "$WORKSPACE/registry-manifest.json"
                        exit 1
                    fi

                    echo "Registry confirmed the image manifest."
                    rm -f "$WORKSPACE/registry-manifest.json"
                '''
            }
        }

        stage('Rollback Application') {
            steps {
                sh '''
                    set -eu

                    export PATH="$WORKSPACE/bin:$PATH"
                    export KUBECONFIG="$WORKSPACE/.kube/config"

                    IMAGE="$REGISTRY/$APP_NAME:$IMAGE_VERSION"

                    CONTAINER_NAME=$(kubectl get deployment "$APP_NAME" \
                        -n "$NAMESPACE" \
                        -o jsonpath='{.spec.template.spec.containers[0].name}')

                    if [ -z "$CONTAINER_NAME" ]; then
                        echo "ERROR: Could not determine container name."
                        exit 1
                    fi

                    echo "Rolling back to $IMAGE"

                    kubectl set image \
                        "deployment/$APP_NAME" \
                        "$CONTAINER_NAME=$IMAGE" \
                        -n "$NAMESPACE"

                    kubectl rollout status \
                        "deployment/$APP_NAME" \
                        -n "$NAMESPACE" \
                        --timeout=180s

                    echo "Rollback completed."
                '''
            }
        }

        stage('Verify Final State') {
            steps {
                sh '''
                    set -eu

                    export PATH="$WORKSPACE/bin:$PATH"
                    export KUBECONFIG="$WORKSPACE/.kube/config"

                    kubectl get deployment "$APP_NAME" \
                        -n "$NAMESPACE" -o wide

                    kubectl get pods -n "$NAMESPACE" -o wide
                    kubectl get services -n "$NAMESPACE"
                '''
            }
        }
    }

    post {
        success {
            echo "Rollback successful: ${params.APP_NAME}, cluster ${params.CLUSTER}, version ${params.IMAGE_VERSION}"
        }

        failure {
            echo 'Rollback failed. Check the Jenkins console output.'
        }

        always {
            echo 'Rollback pipeline finished.'
        }
    }
}
